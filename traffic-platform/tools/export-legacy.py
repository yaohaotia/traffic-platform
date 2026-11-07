"""Read-only legacy export. No passwords, role grants, balances, bank details or remote image fetches."""
import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit
import pymysql
import yaml


def main():
    project = Path(__file__).resolve().parents[1]
    workspace = project.parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, default=project.parent / "server（后端）/src/main/resources/application.yml")
    parser.add_argument("--output", type=Path, default=project.parent / "docs/migration/private/legacy-export.json")
    parser.add_argument("--page", type=int, default=1)
    parser.add_argument("--size", type=int, default=1000)
    parser.add_argument("--all-users", action="store_true", help="Include all source accounts, up to the explicit 1000-account batch limit")
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(workspace) or not 1 <= args.size <= 1000 or args.page < 1:
        raise ValueError("Output must remain in the workspace; page>=1 and size=1..1000 required")
    if output.exists():
        raise ValueError("Existing export will not be overwritten; choose a new output filename")
    config = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    spring = config.get("spring", {})
    source = spring.get("datasource", {})
    if not source.get("url"):
        active = spring.get("profiles", {}).get("active", "")
        if active and active.replace("-", "").isalnum():
            profile_file = args.config.parent / ("application-" + active + ".yml")
            profile = yaml.safe_load(profile_file.read_text(encoding="utf-8"))
            source = profile.get("spring", {}).get("datasource", {})
    if not source.get("url") and isinstance(source.get("druid"), dict):
        source = source["druid"]
    url = urlsplit(source["url"].removeprefix("jdbc:"))
    if url.scheme != "mysql" or not url.hostname or not url.path.strip("/"):
        raise ValueError("A resolved legacy MySQL URL is required")
    with pymysql.connect(host=url.hostname, port=url.port or 3306, database=url.path.strip("/"),
                         user=source["username"], password=str(source["password"]),
                         charset="utf8mb4", connect_timeout=5, read_timeout=10,
                         autocommit=False, cursorclass=pymysql.cursors.DictCursor) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            connection.begin()
            cursor.execute("SELECT @@session.transaction_read_only AS readonly")
            if cursor.fetchone()["readonly"] != 1:
                raise RuntimeError("Legacy source did not enter a read-only transaction")
            cursor.execute("SELECT COUNT(*) AS count FROM admin")
            total_users = cursor.fetchone()["count"]
            cursor.execute("SELECT COUNT(*) AS count FROM reports")
            total_reports = cursor.fetchone()["count"]
            cursor.execute("SELECT id, user_id, status, COALESCE(location,'') AS location, COALESCE(description,'') AS description FROM reports ORDER BY id LIMIT %s OFFSET %s",
                           (args.size, (args.page - 1) * args.size))
            reports = cursor.fetchall()
            ids = sorted({row["user_id"] for row in reports})
            if args.all_users:
                if total_users > 1000:
                    raise ValueError("All-user export exceeds the reviewed 1000-account batch limit")
                cursor.execute("SELECT id, username, status FROM admin ORDER BY id")
            elif ids:
                cursor.execute("SELECT id, username, status FROM admin WHERE id IN (" + ",".join(["%s"] * len(ids)) + ") ORDER BY id", ids)
            else:
                cursor.execute("SELECT id, username, status FROM admin ORDER BY id LIMIT %s OFFSET %s", (args.size, (args.page - 1) * args.size))
            users = cursor.fetchall()
        connection.rollback()  # No commit and no DDL/DML against the original database.
    bundle = {"schemaVersion": 1,
              "users": [{"legacyId": str(row["id"]), "username": row["username"], "sourceEnabled": row["status"] == 1} for row in users],
              "reports": [{"legacyId": str(row["id"]), "userLegacyId": str(row["user_id"]),
                           "status": row["status"], "location": row["location"], "description": row["description"]} for row in reports]}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"readOnly": True, "sourceTotals": {"users": total_users, "reports": total_reports},
                      "exported": {"users": len(users), "reports": len(reports)}, "page": args.page,
                      "output": str(output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()

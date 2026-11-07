import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Reads from stdin so plaintext passwords never appear in process arguments. */
public class HashPassword {
    public static void main(String[] args)throws Exception{
        String value=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();
        if(value==null||value.length()<12||value.getBytes(StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("Invalid bootstrap password");
        System.out.print(new BCryptPasswordEncoder(12).encode(value));
    }
}

package com.yao.traffic.evidence.application;

import com.yao.traffic.evidence.domain.Evidence;
import com.yao.traffic.evidence.domain.EvidenceContent;
import com.yao.traffic.evidence.domain.EvidenceStorage;
import com.yao.traffic.evidence.infrastructure.EvidenceContentReader;
import com.yao.traffic.evidence.infrastructure.EvidenceRepository;
import com.yao.traffic.shared.security.CurrentActor;
import com.yao.traffic.shared.web.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class EvidenceService {
    private final EvidenceStorage storage;
    private final EvidenceRepository evidence;
    private final EvidenceContentReader contentReader;
    private final TransactionTemplate transactions;
    private final int maxBytes;
    private final long maxPixels;

    public EvidenceService(EvidenceStorage storage, EvidenceRepository evidence, EvidenceContentReader contentReader, PlatformTransactionManager manager,
            @Value("${traffic.report.max-image-bytes:5242880}") int maxBytes,
            @Value("${traffic.report.max-image-pixels:20000000}") long maxPixels) {
        if (maxBytes < 1 || maxBytes > 5 * 1024 * 1024 || maxPixels < 1 || maxPixels > 20_000_000)
            throw new IllegalArgumentException("Image safety limits may be reduced, not enlarged beyond the supported bounds");
        this.storage = storage; this.evidence = evidence; this.contentReader=contentReader; this.transactions = new TransactionTemplate(manager);
        this.maxBytes = maxBytes; this.maxPixels = maxPixels;
    }

    @PreAuthorize("hasAuthority('report:submit')")
    public Evidence.Metadata upload(MultipartFile image) {
        if (image == null || image.isEmpty() || image.getSize() > maxBytes) throw invalidImage();
        byte[] content;
        try (var stream = image.getInputStream()) { content = stream.readNBytes(maxBytes + 1); }
        catch (IOException exception) { throw invalidImage(); }
        if (content.length == 0 || content.length > maxBytes) throw invalidImage();
        String extension = imageFormat(content);
        String hash = sha256(content);
        long owner = CurrentActor.profile().userId();
        String key = storage.write(content, extension);
        try {
            return transactions.execute(status -> evidence.insert(owner, key, hash,
                    extension.equals("png") ? "image/png" : "image/jpeg", content.length).metadata());
        } catch (RuntimeException exception) {
            try { storage.delete(key); } catch (RuntimeException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
    }

    @PreAuthorize("hasAuthority('report:read') or hasAuthority('report:submit') or hasAuthority('report:review')")
    public Evidence.Metadata metadata(long id) {
        Evidence item = evidence.required(id);
        if (item.ownerId() != CurrentActor.profile().userId())
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "无权查看此图片证据");
        return item.metadata();
    }

    @PreAuthorize("hasAuthority('report:read') or hasAuthority('report:submit') or hasAuthority('report:review')")
    public EvidenceContent content(long id){
        Evidence item=evidence.required(id);
        if(item.ownerId()!=CurrentActor.profile().userId())
            throw new BusinessException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","无权查看此图片证据");
        return contentReader.read(item);
    }

    private String imageFormat(byte[] content) {
        ImageReader reader = null;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            reader = readers.next();
            String format = reader.getFormatName().toLowerCase(Locale.ROOT);
            String extension = switch (format) { case "png" -> "png"; case "jpg", "jpeg" -> "jpg"; default -> throw invalidImage(); };
            reader.setInput(input, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width <= 0 || height <= 0 || (long) width * height > maxPixels) throw invalidImage();
            if (reader.read(0) == null) throw invalidImage();
            return extension;
        } catch (IOException exception) { throw invalidImage(); }
        catch (RuntimeException exception) { if (exception instanceof BusinessException business) throw business; throw invalidImage(); }
        finally { if (reader != null) reader.dispose(); }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static BusinessException invalidImage() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "仅支持大小和像素受限的有效PNG或JPEG图片");
    }
}

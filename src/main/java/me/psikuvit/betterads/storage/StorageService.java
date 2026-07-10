package me.psikuvit.betterads.storage;

import com.amazonaws.HttpMethod;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.Optional;

@Service
public class StorageService {

    private final AmazonS3 s3;
    private final String bucket;

    public StorageService(@Value("${app.s3.bucket:}") String bucket,
                          @Value("${app.s3.region:us-east-1}") String region) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "app.s3.bucket must be configured — refusing to start without a target bucket for uploads");
        }
        this.bucket = bucket;
        this.s3 = AmazonS3ClientBuilder.standard()
                .withRegion(region)
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .build();
    }

    public String presignPutUrl(String key, String contentType, Duration duration) {
        Date expiration = new Date(System.currentTimeMillis() + duration.toMillis());
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(bucket, key)
                .withMethod(HttpMethod.PUT)
                .withExpiration(expiration)
                .withContentType(contentType);
        // Binding contentType into the signed request means the client's PUT must send
        // a matching Content-Type header, or S3 will reject it with SignatureDoesNotMatch.
        return s3.generatePresignedUrl(req).toString();
    }

    public Optional<ObjectMetadata> getObjectMetadata(String key) {
        try {
            return Optional.of(s3.getObjectMetadata(bucket, key));
        } catch (AmazonS3Exception e) {
            return Optional.empty();
        }
    }
}

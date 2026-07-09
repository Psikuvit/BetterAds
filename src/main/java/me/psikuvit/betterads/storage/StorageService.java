package me.psikuvit.betterads.storage;

import com.amazonaws.HttpMethod;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;

@Service
public class StorageService {

    private final AmazonS3 s3;
    private final String bucket;

    public StorageService(@Value("${app.s3.bucket:}") String bucket,
                          @Value("${app.s3.region:us-east-1}") String region) {
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
                .withExpiration(expiration);
        // contentType can't be set on presign request in v1 SDK; clients should set it when uploading
        return s3.generatePresignedUrl(req).toString();
    }
}



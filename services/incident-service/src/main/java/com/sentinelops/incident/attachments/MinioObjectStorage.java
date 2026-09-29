package com.sentinelops.incident.attachments;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import org.springframework.stereotype.Component;

@Component
public class MinioObjectStorage implements ObjectStorage {
  private final MinioClient client;
  private final String bucket;

  public MinioObjectStorage(AttachmentProperties properties) {
    this.client =
        MinioClient.builder()
            .endpoint(properties.endpoint())
            .credentials(properties.accessKey(), properties.secretKey())
            .build();
    this.bucket = properties.bucket();
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    try {
      client.putObject(
          PutObjectArgs.builder().bucket(bucket).object(key).stream(
                  new ByteArrayInputStream(content), content.length, -1)
              .contentType(contentType)
              .build());
    } catch (Exception e) {
      throw new AttachmentStorageException("Could not store attachment", e);
    }
  }

  @Override
  public byte[] get(String key) {
    try (var stream =
        client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
      return stream.readAllBytes();
    } catch (Exception e) {
      throw new AttachmentStorageException("Could not read attachment", e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      throw new AttachmentStorageException("Could not delete attachment", e);
    }
  }
}

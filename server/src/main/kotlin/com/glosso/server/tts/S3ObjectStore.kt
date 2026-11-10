package com.glosso.server.tts

import java.net.URI
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * S3-compatible object store (Cloudflare R2, AWS S3, MinIO...). Built from the
 * `GLOSSO_R2_*` environment variables, so the same code targets any provider by
 * changing the endpoint. Never versions credentials.
 */
class S3ObjectStore(
    private val client: S3Client,
    private val bucket: String,
) : ObjectStore {

    override val id: String = "s3"

    override fun exists(key: String): Boolean = try {
        client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        true
    } catch (e: NoSuchKeyException) {
        false
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) false else throw e
    }

    override fun put(key: String, bytes: ByteArray, contentType: String): ObjectRef {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(bytes),
        )
        return ObjectRef(key, bytes.size.toLong(), contentType)
    }

    override fun get(key: String): ByteArray? = try {
        client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()
    } catch (e: NoSuchKeyException) {
        null
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) null else throw e
    }

    override fun delete(key: String) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
    }

    override fun close() {
        client.close()
    }

    companion object {
        fun fromConfig(config: R2Config?): S3ObjectStore? {
            if (config == null) return null
            val client = S3Client.builder()
                .endpointOverride(URI.create(config.endpoint))
                .region(Region.of(config.region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.accessKey, config.secretKey),
                    ),
                )
                .forcePathStyle(config.pathStyle)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()
            return S3ObjectStore(client, config.bucket)
        }
    }
}

package org.example.auth;

import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.params.AwsS3V4SignerParams;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AwsV4OutboundSigner {

    private static final AwsS3V4Signer SIGNER = AwsS3V4Signer.create();

    public static Map<String, String> sign(String method, URI url, Map<String, String> headers,
                                            byte[] body, String accessKey, String secretKey,
                                            String region, String service) {
        SdkHttpFullRequest.Builder requestBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .uri(url);

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.putHeader(entry.getKey(), entry.getValue());
            }
        }

        if (body != null && body.length > 0) {
            requestBuilder.contentStreamProvider(ContentStreamProvider.fromByteArray(body));
        }

        AwsS3V4SignerParams params = AwsS3V4SignerParams.builder()
                .awsCredentials(AwsBasicCredentials.create(accessKey, secretKey))
                .signingRegion(Region.of(region))
                .signingName(service)
                .enablePayloadSigning(false)
                .build();

        SdkHttpFullRequest signedRequest = SIGNER.sign(requestBuilder.build(), params);

        Map<String, String> signingHeaders = new HashMap<>();
        Map<String, List<String>> allHeaders = signedRequest.headers();
        extractHeader(allHeaders, "Authorization", signingHeaders);
        extractHeader(allHeaders, "X-Amz-Date", signingHeaders);
        extractHeader(allHeaders, "x-amz-content-sha256", signingHeaders);
        return signingHeaders;
    }

    private static void extractHeader(Map<String, List<String>> allHeaders, String name, Map<String, String> target) {
        List<String> values = allHeaders.get(name);
        if (values != null && !values.isEmpty()) {
            target.put(name, values.get(values.size() - 1));
        }
    }
}

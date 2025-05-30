package com.fortanix.jenkins.credentials;

import com.cloudbees.jenkins.plugins.kubernetes_credentials_provider.CredentialsConvertionException;
import com.cloudbees.jenkins.plugins.kubernetes_credentials_provider.SecretToCredentialConverter;
import hudson.Extension;
import io.fabric8.kubernetes.api.model.Secret;
import com.cloudbees.plugins.credentials.CredentialsScope;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Converts Kubernetes secrets to Fortanix DSM credentials.
 * This converter integrates with the Kubernetes Credentials Provider Plugin to enable
 * automatic creation of Fortanix credentials from Kubernetes secrets.
 *
 * Requires the Kubernetes Credentials Provider Plugin to be installed:
 * https://plugins.jenkins.io/kubernetes-credentials-provider/
 *
 * Example secret format:
 * <pre>
 * apiVersion: v1
 * kind: Secret
 * metadata:
 *   name: fortanix-credentials
 *   annotations:
 *     jenkins.io/credentials-description: "Fortanix DSM Credentials"
 *   labels:
 *     jenkins.io/credentials-type: "com.fortanix.jenkins.credentials.ClientCredentials"
 * type: Opaque
 * data:
 *   apiKey: <base64-encoded-api-key>
 *   apiEndpoint: <base64-encoded-api-endpoint>
 * </pre>
 *
 * @see ClientCredentials
 * @see SecretToCredentialConverter
 * @see <a href="https://plugins.jenkins.io/kubernetes-credentials-provider/">Kubernetes Credentials Provider Plugin</a>
 */
@Extension(optional = true)
public class KubernetesCredentialConverter extends SecretToCredentialConverter {
    private static final Logger LOGGER = Logger.getLogger(KubernetesCredentialConverter.class.getName());
    private static final String CREDENTIALS_TYPE = "com.fortanix.jenkins.credentials.ClientCredentials";
    private static final String API_KEY_FIELD = "apiKey";
    private static final String API_ENDPOINT_FIELD = "apiEndpoint";
    private static final String DESCRIPTION_ANNOTATION = "jenkins.io/credentials-description";

    /**
     * Checks if this converter can handle the given credential type.
     * This method is called by the Kubernetes Credentials Provider Plugin to determine
     * if this converter should handle a particular secret.
     *
     * @param type The fully qualified class name of the credential type
     * @return true if this converter can handle the credential type
     */
    @Override
    public boolean canConvert(String type) {
        LOGGER.log(Level.FINE, "Checking if can convert type: {0}", type);
        return CREDENTIALS_TYPE.equals(type);
    }

    /**
     * Converts a Kubernetes Secret into a Fortanix ClientCredentials object.
     * This method is called by the Kubernetes Credentials Provider Plugin when a secret
     * with the matching type is found in the Kubernetes cluster.
     *
     * @param secret The Kubernetes Secret to convert
     * @return A new ClientCredentials instance
     * @throws CredentialsConvertionException if the conversion fails
     */
    @Override
    public ClientCredentials convert(Secret secret) throws CredentialsConvertionException {
        if (secret == null || secret.getMetadata() == null) {
            throw new CredentialsConvertionException("Secret or its metadata is null");
        }

        String secretName = secret.getMetadata().getName();
        LOGGER.log(Level.FINE, "Converting secret: {0}", secretName);

        Map<String, String> data = secret.getData();
        if (data == null) {
            throw new CredentialsConvertionException("Secret data is null for secret: " + secretName);
        }

        try {
            Map<String, String> annotations = secret.getMetadata().getAnnotations();
            String description = (annotations != null && annotations.containsKey(DESCRIPTION_ANNOTATION)) ? annotations.get(DESCRIPTION_ANNOTATION) : "";

            // Get apiKey
            String apiKey = Optional.ofNullable(data.get(API_KEY_FIELD))
                    .map(key -> new String(Base64.getDecoder().decode(key)))
                    .orElseThrow(() -> new CredentialsConvertionException(
                            String.format("No %s found in secret: %s", API_KEY_FIELD, secretName)));

            // Get apiEndpoint
            String apiEndpoint = Optional.ofNullable(data.get(API_ENDPOINT_FIELD))
                    .map(endpoint -> new String(Base64.getDecoder().decode(endpoint)))
                    .orElseThrow(() -> new CredentialsConvertionException(
                            String.format("No %s found in secret: %s", API_ENDPOINT_FIELD, secretName)));

            LOGGER.log(Level.FINE, "Successfully converted secret: {0}", secretName);
            return new ClientCredentials(CredentialsScope.GLOBAL,
                                       secretName,
                                       description,
                                       apiEndpoint,
                                       hudson.util.Secret.fromString(apiKey));
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Invalid base64 encoding in secret: {0}", secretName);
            throw new CredentialsConvertionException("Invalid base64 encoding in secret: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to convert secret: {0}", e.getMessage());
            throw new CredentialsConvertionException("Failed to convert credentials: " + e.getMessage());
        }
    }
}

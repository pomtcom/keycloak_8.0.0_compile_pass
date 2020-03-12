package org.keycloak.vault;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.keycloak.vault.SecretContains.secretContains;

/**
 * Tests for {@link FilesPlainTextVaultProvider}.
 *
 * @author Sebastian Łaskawiec
 */
public class PlainTextVaultProviderTest {

    @Test
    public void shouldObtainSecret() throws Exception {
        //given
        FilesPlainTextVaultProvider provider = new FilesPlainTextVaultProvider(Scenario.EXISTING.getPath(), "test");

        //when
        VaultRawSecret secret1 = provider.obtainSecret("key1");

        //then
        assertNotNull(secret1);
        assertNotNull(secret1.get().get());
        assertThat(secret1, secretContains("secret1"));
    }

    @Test
    public void shouldReplaceUnderscoreWithTwoUnderscores() throws Exception {
        //given
        FilesPlainTextVaultProvider provider = new FilesPlainTextVaultProvider(Scenario.EXISTING.getPath(), "test_realm");

        //when
        VaultRawSecret secret1 = provider.obtainSecret("underscore_key1");

        //then
        assertNotNull(secret1);
        assertNotNull(secret1.get().get());
        assertThat(secret1, secretContains("underscore_secret1"));
    }

    @Test
    public void shouldReturnEmptyOptionalOnMissingSecret() throws Exception {
        //given
        FilesPlainTextVaultProvider provider = new FilesPlainTextVaultProvider(Scenario.EXISTING.getPath(), "test");

        //when
        VaultRawSecret secret = provider.obtainSecret("non-existing-key");

        //then
        assertNotNull(secret);
        assertFalse(secret.get().isPresent());
    }

    @Test
    public void shouldOperateOnNonExistingVaultDirectory() throws Exception {
        //given
        FilesPlainTextVaultProvider provider = new FilesPlainTextVaultProvider(Scenario.NON_EXISTING.getPath(), "test");

        //when
        VaultRawSecret secret = provider.obtainSecret("non-existing-key");

        //then
        assertNotNull(secret);
        assertFalse(secret.get().isPresent());
    }

    @Test
    public void shouldReflectChangesInASecretFile() throws Exception {
        //given
        Path temporarySecretFile = Files.createTempFile("vault", null);
        Path vaultDirectory = temporarySecretFile.getParent();
        String secretName = temporarySecretFile.getFileName().toString();

        FilesPlainTextVaultProvider provider = new FilesPlainTextVaultProvider(vaultDirectory, "ignored") {
            @Override
            protected Path resolveSecretPath(String vaultSecretId) {
                return vaultDirectory.resolve(vaultSecretId);
            }
        };

        //when
        String secret1AsString = null;
        String secret2AsString = null;

        Files.write(temporarySecretFile, "secret1".getBytes());
        try (VaultRawSecret secret1 = provider.obtainSecret(secretName)) {
            secret1AsString = StandardCharsets.UTF_8.decode(secret1.get().get()).toString();
        }

        Files.write(temporarySecretFile, "secret2".getBytes());
        try (VaultRawSecret secret2 = provider.obtainSecret(secretName)) {
            secret2AsString = StandardCharsets.UTF_8.decode(secret2.get().get()).toString();
        }

        //then
        assertEquals("secret1", secret1AsString);
        assertEquals("secret2", secret2AsString);
    }

    @Test
    public void shouldNotOverrideFileWhenDestroyingASecret() throws Exception {
        //given
        Path temporarySecretFile = Files.createTempFile("vault", null);
        Path vaultDirectory = temporarySecretFile.getParent();
        String secretName = temporarySecretFile.getFileName().toString();

        FilesPlainTextVaultProvider provider = new FilesPlainTextVaultProvider(vaultDirectory, "ignored") {
            @Override
            protected Path resolveSecretPath(String vaultSecretId) {
                return vaultDirectory.resolve(vaultSecretId);
            }
        };

        Files.write(temporarySecretFile, "secret".getBytes());

        //when
        VaultRawSecret secretAfterFirstRead = provider.obtainSecret(secretName);
        secretAfterFirstRead.close();
        VaultRawSecret secretAfterSecondRead = provider.obtainSecret(secretName);

        //then
        assertThat(secretAfterFirstRead, secretContains("secret"));
        assertThat(secretAfterSecondRead, secretContains("secret"));
    }
}
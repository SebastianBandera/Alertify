package app.alertify.binary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.jpa.repository.ConfigurationBinaryValueRepository;
import app.alertify.jpa.repository.SecretBinaryValueRepository;
import app.alertify.services.secret.SecretEncryptionService;

@Service
public class BinaryBindingService {
    private final ConfigurationBinaryValueRepository configurations;
    private final SecretBinaryValueRepository secrets;
    private final SecretEncryptionService encryption;

    public BinaryBindingService(ConfigurationBinaryValueRepository configurations, SecretBinaryValueRepository secrets, SecretEncryptionService encryption) {
        this.configurations = configurations; this.secrets = secrets; this.encryption = encryption;
    }

    @Transactional(readOnly = true)
    public byte[] configurationZip(long id) {
        return configurations.findById(id).orElseThrow(() -> new IllegalStateException("Binary configuration payload is missing")).getZipValue();
    }

    @Transactional(readOnly = true)
    public byte[] secretZip(long id) {
        return encryption.decryptBinary(secrets.findById(id).orElseThrow(() -> new IllegalStateException("Binary secret payload is missing")));
    }
}

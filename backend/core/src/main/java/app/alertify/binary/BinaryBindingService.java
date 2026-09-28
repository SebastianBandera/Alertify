package app.alertify.binary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.jpa.repository.ConfigurationBinaryValueRepository;

@Service
public class BinaryBindingService {
    private final ConfigurationBinaryValueRepository configurations;

    public BinaryBindingService(ConfigurationBinaryValueRepository configurations) {
        this.configurations = configurations;
    }

    @Transactional(readOnly = true)
    public byte[] configurationZip(long id) {
        return configurations.findById(id).orElseThrow(() -> new IllegalStateException("Binary configuration payload is missing")).getZipValue();
    }
}

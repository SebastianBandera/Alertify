package app.alertify.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "BasicSecret")
@Table(schema = "secrets", name = "basicsecret")
public class BasicSecret {
	
	public static final int SECRET_STATUS_PLAIN = 0;
	public static final int SECRET_STATUS_ENCRYPTED_AES_SHA256_IV = 1;

    @Id
    @Column(name = "id", nullable = false)
    private long id;

    @Column(name = "secretstatus", nullable = false)
    private int secretStatus;

    @Column(name = "secret", nullable = false)
    private String secret;

    public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	public int getSecretStatus() {
		return secretStatus;
	}

	public void setSecretStatus(int secretstatus) {
		this.secretStatus = secretstatus;
	}

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}
}

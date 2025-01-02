package app.alertify.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity(name = "DbSource")
@Table(schema = "alert", name = "dbsources")
public class DbSource {
	
	public static final int PASSWORD_STATUS_PLAIN = 0;
	public static final int PASSWORD_STATUS_ENCRYPTED_AES_SHA256_IV = 1;

    @Id
    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "readonly", nullable = false)
    private boolean readonly;

    @Column(name = "url", nullable = false)
    private String url;

    @Column(name = "driverclassname", nullable = false)
    private String driverClassName;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "passwordstatus", nullable = false)
    private int passwordStatus;

    @Column(name = "password", nullable = false)
    private String password;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isReadonly() {
        return readonly;
    }

    public void setReadonly(boolean readonly) {
        this.readonly = readonly;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getPasswordStatus() {
        return passwordStatus;
    }

    public void setPasswordStatus(int passwordStatus) {
        this.passwordStatus = passwordStatus;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}

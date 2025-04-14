package app.alertify.entity;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

@Entity(name = "DbSource")
@Table(schema = "conn", name = "dbsources")
public class DbSource {

    @Id
    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "readonly", nullable = false)
    private boolean readonly;

    @Column(name = "url", nullable = false)
    private String url;

    @Column(name = "driverclassname", nullable = false)
    private String driverClassName;
    
    @ManyToOne(cascade = CascadeType.MERGE)
    @JoinColumn(name = "id_username", nullable = false)
    private BasicSecret basicSecretUsername;
	  
    @ManyToOne(cascade = CascadeType.MERGE)
    @JoinColumn(name = "id_password", nullable = false)
    private BasicSecret basicSecretPassword;

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

	public BasicSecret getBasicSecretUsername() {
		return basicSecretUsername;
	}

	public void setBasicSecretUsername(BasicSecret basicSecretUsername) {
		this.basicSecretUsername = basicSecretUsername;
	}

	public BasicSecret getBasicSecretPassword() {
		return basicSecretPassword;
	}

	public void setBasicSecretPassword(BasicSecret basicSecretPassword) {
		this.basicSecretPassword = basicSecretPassword;
	}
}

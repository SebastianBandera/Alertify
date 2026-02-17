package app.alertify.entity;

import java.util.Date;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.UniqueConstraint;

@Entity(name = "ConfigTimestamp")
@Table(schema = "config", name = "config_timestamp", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class ConfigTimestamp {

	@Id
    @Column(name = "name", length = 100)
    private String name;

	@Temporal(TemporalType.TIMESTAMP)
	@Column(name = "config_timestamp")
	private Date configTimestamp;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Date getConfigTimestamp() {
        return configTimestamp;
    }

    public void setConfigTimestamp(Date configTimestamp) {
        this.configTimestamp = configTimestamp;
    }
}

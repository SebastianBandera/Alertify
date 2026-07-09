package app.alertify.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity(name = "ConfigText")
@Table(schema = "config", name = "config_text", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class ConfigText {

	@Id
    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "config_text")
    private String configText;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getConfigText() {
        return configText;
    }

    public void setConfigInt(String configText) {
        this.configText = configText;
    }
}

package app.alertify.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

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

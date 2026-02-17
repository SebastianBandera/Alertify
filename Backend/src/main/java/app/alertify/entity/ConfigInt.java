package app.alertify.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity(name = "ConfigInt")
@Table(schema = "config", name = "config_int", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class ConfigInt {

	@Id
    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "config_int")
    private Integer configInt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getConfigInt() {
        return configInt;
    }

    public void setConfigInt(Integer configInt) {
        this.configInt = configInt;
    }
}

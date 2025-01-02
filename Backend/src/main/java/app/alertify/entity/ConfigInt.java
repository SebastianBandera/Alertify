package app.alertify.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

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

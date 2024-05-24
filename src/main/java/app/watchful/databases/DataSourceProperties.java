package app.watchful.databases;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "datasources")
public class DataSourceProperties {

    private List<DataSourceConfig> datasources;

    public List<DataSourceConfig> getDatasources() {
        return datasources;
    }
    
    //Map<String, Object> mejora parse, {0={datasource={name=base1, readonly=true, url=jdbc:postgresql://localhost:54321/base1, username=postgres, password=postgres, driverClassName=org.postgresql.Driver}}, 1={datasource={name=base2, readonly=true, url=jdbc:postgresql://localhost:54321/base2, username=postgres, password=postgres, driverClassName=org.postgresql.Driver}}}
	public void setConfig(Map<String, String> config) {
		if (config == null) {
			datasources = new ArrayList<>();
		} else {
			List<String> sortedKeys = config.keySet().stream().sorted().collect(Collectors.toList());
			
			Map<String, List<String>> groupedKeys = sortedKeys.stream().collect(Collectors.groupingBy(str -> {
				if(str==null) return "null";
				String[] split = str.split("\\.");
				if(split==null || split.length!=3) return "unknown";
				return str.split("\\.")[0];
			}));
			
			List<DataSourceConfig> result = new ArrayList<>();
			
			groupedKeys.forEach((key, list) -> {
				DataSourceConfig dataSourceConfig = parse(list, config);
				if(dataSourceConfig!=null) result.add(dataSourceConfig);
			});
			
			datasources = result;
		}
	}

	private DataSourceConfig parse(List<String> list, Map<String, String> config) {
		DataSourceConfig dataSourceConfig = new DataSourceConfig();
		
		try {
			Map<String, List<String>> values = list.stream().collect(Collectors.groupingBy(str -> str.split("\\.")[2]));
			
			dataSourceConfig.setName((String)config.get(values.get("name").get(0)));
			dataSourceConfig.setUrl((String)config.get(values.get("url").get(0)));
			dataSourceConfig.setUsername((String)config.get(values.get("username").get(0)));
			dataSourceConfig.setPassword((String)config.get(values.get("password").get(0)));
			dataSourceConfig.setDriverClassName((String)config.get(values.get("driverClassName").get(0)));
			dataSourceConfig.setReadonly(config.get(values.get("readonly").get(0)).equals("true"));
			
			return dataSourceConfig;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
	
	@Data
    public static class DataSourceBase {
        private DataSourceConfig datasource;
    }

	@Data
    public static class DataSourceConfig {
        private String name;
        private Boolean readonly;
        private String url;
        private String username;
        private String password;
        private String driverClassName;
    }
}
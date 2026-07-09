package app.alertify.databases;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class DataSourceProperties {

    private List<DataSourceConfig> datasources;
    
    public DataSourceProperties() {
    	datasources = new LinkedList<DataSourceProperties.DataSourceConfig>();
    }

	public List<DataSourceConfig> getDatasources() {
        return datasources;
    }
	
	public void removeByName(String name) {
		Iterator<DataSourceConfig> iter = datasources.iterator();
		while(iter.hasNext()) {
			DataSourceConfig data = iter.next();
			
			if (data != null && data.getName() != null && data.getName().equals(name)) {
				iter.remove();
			}
		}
	}

	public static class DataSourceConfig {
        private String name;
        private Boolean readonly;
        private String url;
        private String username;
        private String password;
        private String driverClassName;
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public Boolean getReadonly() {
			return readonly;
		}
		public void setReadonly(Boolean readonly) {
			this.readonly = readonly;
		}
		public String getUrl() {
			return url;
		}
		public void setUrl(String url) {
			this.url = url;
		}
		public String getUsername() {
			return username;
		}
		public void setUsername(String username) {
			this.username = username;
		}
		public String getPassword() {
			return password;
		}
		public void setPassword(String password) {
			this.password = password;
		}
		public String getDriverClassName() {
			return driverClassName;
		}
		public void setDriverClassName(String driverClassName) {
			this.driverClassName = driverClassName;
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(driverClassName, name, password, readonly, url, username);
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			DataSourceConfig other = (DataSourceConfig) obj;
			return Objects.equals(driverClassName, other.driverClassName) && Objects.equals(name, other.name)
					&& Objects.equals(password, other.password) && Objects.equals(readonly, other.readonly)
					&& Objects.equals(url, other.url) && Objects.equals(username, other.username);
		}
		@Override
		public String toString() {
			return "DataSourceConfig [name=" + name + ", readonly=" + readonly + ", url=" + url + ", driverClassName=" + driverClassName + "]";
		}
    }
}
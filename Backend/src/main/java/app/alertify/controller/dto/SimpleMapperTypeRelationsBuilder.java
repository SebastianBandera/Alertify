package app.alertify.controller.dto;

import java.util.HashMap;
import java.util.Map;

public class SimpleMapperTypeRelationsBuilder {

	private Map<Class<?>, Class<?>> typeRelations;
		
	public SimpleMapperTypeRelationsBuilder() {
			this.typeRelations = new HashMap<>();
	}
	
	public SimpleMapperTypeRelationsBuilder add(Class<?> a, Class<?> b) {
		this.typeRelations.put(a, b);
		return this;
	}
	
	public Map<Class<?>, Class<?>> build() {
		return this.typeRelations;
	}
	
	public static SimpleMapperTypeRelationsBuilder newInstance() {
		return new SimpleMapperTypeRelationsBuilder();
	}
}

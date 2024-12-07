package app.alertify.controller.dto;

import java.util.Map;

import org.springframework.stereotype.Service;

import app.alertify.entity.Alert;
import app.alertify.entity.AlertResult;
import app.alertify.entity.CodStatus;

@Service
public class MapperConfig {

	private final Map<Class<?>, Class<?>> MAP;
	
	public MapperConfig() {
		this.MAP = SimpleMapperTypeRelationsBuilder.newInstance()
				.add(Alert.class, AlertDto.class)
				.add(AlertResult.class, AlertResultDto.class)
				.add(CodStatus.class, CodStatusDto.class)
				.build();
	}
	
	public Map<Class<?>, Class<?>> getMapping() {
		return this.MAP;
	}
}

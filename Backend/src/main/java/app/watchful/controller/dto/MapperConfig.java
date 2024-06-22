package app.watchful.controller.dto;

import java.util.Map;

import org.springframework.stereotype.Service;

import app.watchful.entity.Alert;
import app.watchful.entity.AlertResult;
import app.watchful.entity.CodStatus;

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

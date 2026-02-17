package app.alertify.controller.dto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import app.alertify.config.Global;
import app.alertify.entity.Alert;
import app.alertify.entity.AlertResult;
import app.alertify.entity.CodStatus;

@Service
public class MapperConfig {

	private final Map<Class<?>, Class<?>> MAP;

	private final Map<Class<?>, List<MapFunction>> MAP_FUNCTION;
	
	public MapperConfig() {
		this.MAP = SimpleMapperTypeRelationsBuilder.newInstance()
				.add(Alert.class, AlertDto.class)
				.add(AlertResult.class, AlertResultDto.class)
				.add(CodStatus.class, CodStatusDto.class)
				.build();
		
		this.MAP_FUNCTION = new HashMap<Class<?>, List<MapFunction>>();
		
		this.MAP_FUNCTION.put(AlertDto.class, Arrays.asList(new MapFunction(Alert.class, "control", (control) -> {
			String controlStr = control == null ? "" : generateHash(control.toString());
			return controlStr;
		})));
	}
	
	public Map<Class<?>, Class<?>> getMapping() {
		return this.MAP;
	}

	public Map<Class<?>, List<MapFunction>> getMapFunctions() {
		return MAP_FUNCTION;
	}
	
	private String generateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] encodedhash = digest.digest(input.getBytes(Global.getAppCharset()));
            return bytesToHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

	public class MapFunction {
		private final Class<?> inputClass;
		private final String fieldName;
		private final Function<Object, Object> translate;
		
		public MapFunction(Class<?> inputClass, String fieldName, Function<Object, Object> translate) {
			final String msg = "null param in MapFunction";
			this.inputClass = Objects.requireNonNull(inputClass, msg);
			this.fieldName = Objects.requireNonNull(fieldName, msg);
			this.translate = Objects.requireNonNull(translate, msg);
		}
		
		public Class<?> getInputClass() {
			return inputClass;
		}
		public String getFieldName() {
			return fieldName;
		}
		public Function<Object, Object> getTranslate() {
			return translate;
		}
	}
}

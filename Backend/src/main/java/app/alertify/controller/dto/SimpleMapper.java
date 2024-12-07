package app.alertify.controller.dto;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class SimpleMapper {
	
	public SimpleMapper() {
		
	}

	public <T, K> T map(K input, Class<T> classOutput) {
		return map(input, classOutput, new HashMap<>());
	}

	public <T, K> T map(K input, Class<T> classOutput, Map<Class<?>, Class<?>> typeRelations) {
		try {
			return tryMap(input, classOutput, typeRelations);
		} catch (InstantiationException | IllegalAccessException | SecurityException | InvocationTargetException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	public <T, K> T tryMap(K input, Class<T> classOutput, Map<Class<?>, Class<?>> typeRelations) throws InstantiationException, IllegalAccessException, SecurityException, InvocationTargetException {
		Method[] methods = input.getClass().getDeclaredMethods();
		
		if(methods == null) {
			return null;
		}
		
		T output = classOutput.newInstance();
		
		for (int i = 0; i < methods.length; i++) {
			Method mtd = methods[i];
			String mtdName = mtd.getName();
			String mtdNameSuffix = mtdName.length() > 3 ? mtdName.substring(3) : mtdName;
			if (mtdName.startsWith("get")) {
				try {
					Method setter = findSet(methods, mtdNameSuffix);
					if (setter == null) {
						continue;
					}
					Object valueOut = mtd.invoke(input);
					Class<?>[] types = setter.getParameterTypes();
					if(types != null && types.length > 0) {
						for (int j = 0; j < types.length; j++) {
							Class<?> newType = typeRelations.get(types[j]);
							if (newType != null) {
								types[j] = newType;
								Object newValue = tryMap(valueOut, newType, typeRelations);
								valueOut = newValue;
							}
						}
					}
					Method mtdOut = classOutput.getDeclaredMethod("set" + mtdNameSuffix, types);
					mtdOut.invoke(output, valueOut);
				} catch (NoSuchMethodException | IllegalArgumentException e) {}
			}
		}
		
		return output;
	}

	private Method findSet(Method[] methods, String mtdNameSuffix) {
		Method mtdresult = null;
		
		for (Method mtd : methods) {
			if (mtd.getName().equals("set" + mtdNameSuffix)) {
				mtdresult = mtd;
				break;
			}
		}
		
		return mtdresult;
	}
}

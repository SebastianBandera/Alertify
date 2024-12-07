package app.alertify.startup;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import app.alertify.control.Control;
import app.alertify.control.common.StringUtils;
import app.alertify.databases.DataSources;
import app.alertify.entity.Alert;
import app.alertify.entity.repositories.AlertRepository;
import app.alertify.service.GlobalStatus;
import app.alertify.service.ThreadControl;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class StartupProcess {

	@Autowired
    private ApplicationContext context;
	
	@Autowired
	private GlobalStatus globalStatus;
	
	@Autowired
	private DataSources dataSources;
	
	@Autowired
	private AlertRepository repositorioAlert;
	
	@Autowired
	private ThreadControl threadControl;
	
    public void runnable() {
    	log.info("Startup");
    	
    	boolean correctInit = init();
    	
    	if (correctInit) {
    		globalStatus.setReady();			
		} else {
			closeAppError();
		}
    	
    	log.info("Startup ends");
    }

	private void closeAppError() {
		log.warn("##### Closing app due error on init ##################################################");
		int exitCode = SpringApplication.exit(context, () -> 500);
		System.exit(exitCode);
	}

	private boolean init() {
    	try {
			_init();
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			log.error("error init()", e);
		}
    	
		return false;
	}

	private void _init() {
    	reloadAlerts();
		
	}

	private void reloadAlerts() {
		repositorioAlert.findAll().forEach(this::registerAlert);
		threadControl.startThreadControl();
	}
	
	public void registerAlert(Alert alert) {
		String controlString = alert.getControl();
		
		log.info("Registering init: " + controlString);
		
		Duration interval = alert.getPeriodicity();
		
		if(nullOrZero(interval)) {
			log.info(StringUtils.concat("control ", controlString, " omitted for null or zero interval"));
			return;
		}
		
		Control control = null;
		
		try {
			control = context.getBean(alert.getControl(), Control.class);
		} catch (Exception e) {
			e.printStackTrace();
			log.warn(StringUtils.concat("control ", controlString, " not found"), e);
		}
		
		Map<String, Object> mapParams = parse(alert.getParametrosMap());
		
		threadControl.registerAlertTask(alert, control, mapParams);
	}

	private Map<String, Object> parse(Map<String, Object> mapParams) {
		Iterator<String> iterKeys = mapParams.keySet().iterator();
		while (iterKeys.hasNext()) {
			try {
				String key = iterKeys.next();
				
				Object obj = mapParams.get(key);
				
				if (obj instanceof List<?>) {
					@SuppressWarnings("unchecked")
					List<Object> list = (List<Object>)obj;
					Object[] arr = list.toArray();
					mapParams.put(key, arr);
				}
				
				if (obj instanceof Map<?, ?>) {
					@SuppressWarnings("unchecked")
					Map<String, Object> map = (Map<String, Object>)obj;
					if (map != null && map.containsKey("type") && "datasource".equals((String)map.get("type"))) {
						String value = (String)map.get("value");
						DataSource dataSource = dataSources.getDataSource(value);
						mapParams.put(key, dataSource);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				log.error("error process params", e);
			}
		}
		
		return mapParams;
	}

	private boolean nullOrZero(Duration interval) {
		return interval == null || interval.getSeconds() == 0;
	}
	
}

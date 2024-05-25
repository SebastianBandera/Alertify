package app.watchful.service;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import app.watchful.control.ControlResultStatus;
import app.watchful.entity.CodStatus;
import app.watchful.entity.repositories.CodStatusRepository;

@Service
public class CodStatusService {

	@Autowired
	private CodStatusRepository codStatusRepository;
	
	private final Map<String, CodStatus> mapCache;
	
	public CodStatusService() {
		mapCache = new HashMap<>();
	}
	
	@PostConstruct
	public void init() {
		codStatusRepository.findAll().forEach(codStatus -> mapCache.put(codStatus.getName(), codStatus));
	}
	
	public CodStatus getCodStatus(String name) {
		return mapCache.get(name);
	}
	
	public CodStatus getCodStatus(ControlResultStatus crs) {
		return mapCache.get(crs.toString());
	}
}

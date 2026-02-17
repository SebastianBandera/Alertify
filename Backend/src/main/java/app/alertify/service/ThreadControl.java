package app.alertify.service;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import app.alertify.control.Control;
import app.alertify.control.ControlResultStatus;
import app.alertify.control.common.StringUtils;
import app.alertify.entity.Alert;
import app.alertify.entity.AlertResult;
import app.alertify.entity.repositories.AlertResultRepository;
import tools.jackson.databind.ObjectMapper;

@Service
public class ThreadControl {
	
	private static final Logger log = LoggerFactory.getLogger(ThreadControl.class);

	private final BlockingQueue<TaskRequest> queue;
	private final Thread thread;
	private final ScheduledExecutorService executorService;
	private final List<TaskContext> scheduledFutureList;
	
	private boolean active;
	
	@Autowired
	private AlertResultRepository alertResultRepository;
	
	@Autowired
	private CodStatusService codStatusService;
	
	public ThreadControl() {
		queue = new LinkedBlockingQueue<>();
		
		thread = new Thread(this::listenTaskRequest, "ThreadControl");
		
		scheduledFutureList = new LinkedList<>();
		
		executorService = Executors.newScheduledThreadPool(5, new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);
			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, "ControlThread-" + threadNumber.getAndIncrement());
			}
		});
		
		active = true;
	}
	
	public void registerAlertTask(Alert alert, Control control, Map<String, Object> mapParams) {
		TaskRequest tr = new TaskRequest();
		tr.setAlert(alert);
		tr.setControl(control);
		tr.setMapParams(mapParams);
		queue.add(tr);
	}
	
	public List<Alert> getRegistredAlerts() {
		return scheduledFutureList.stream().map(taskContext -> taskContext.getTask().getTaskRequest().getAlert()).collect(Collectors.toList());
	}
	
	public void startThreadControl() {
		thread.start();
	}
	
	public void requestStopThread() {
		active = false;
		queue.add(null);
	}
	
	private void listenTaskRequest() {
		do {
			TaskRequest task = null;
			try {
				task = queue.take();
			} catch (Exception e) {
				log.error("error obtening task", e);
				active = false;
			}
			if(task != null) {
				try {
					processTaskRequest(task);
				} catch (Exception e) {
					log.error("ERROR: processTaskRequest with " + (task.getAlert() != null ? task.getAlert().getName() : "null"), e);
				}
			}
		} while (active);
	}
	
	private void processTaskRequest(TaskRequest taskRequest) {
		Optional<TaskContext> optTaskContext = scheduledFutureList.stream().filter(taskContext -> taskContext.getTask().getTaskRequest().getAlert().getName().equals(taskRequest.getAlert().getName())).findAny();;
		if (optTaskContext.isPresent()) {
			log.warn(StringUtils.concat("Alert ", taskRequest.getAlert().getName(), " already registred. Reloading."));
			TaskContext tc = optTaskContext.get();
			boolean canCancel = tc.getScheduledFuture().cancel(false);
			log.info(StringUtils.concat("Alert ", taskRequest.getAlert().getName(), " trying to cancel. Result: ", String.valueOf(canCancel)));
			scheduledFutureList.remove(tc);
		}
		
		long period = taskRequest.getAlert().getPeriodicity().getSeconds();
		
		Task task = new Task();
		task.setAlertResultRepository(alertResultRepository);
		task.setCodStatusService(codStatusService);
		task.setNextExecutionTime(period);
		task.setTaskRequest(taskRequest);
		
		Date now = new Date();
		Date lastAlertResult = alertResultRepository.findLastSuccessDateAlertResultByAlert(taskRequest.getAlert().getId());

		long lastEventInSeconds;
		if (lastAlertResult == null) {
			lastEventInSeconds = Long.MAX_VALUE;
		} else {
			lastEventInSeconds = Math.abs(lastAlertResult.getTime() - now.getTime()) / 1000;
		}
		long delay = lastEventInSeconds < period ? period - lastEventInSeconds : 0;
		
		ScheduledFuture<?> scheduledFuture = executorService.scheduleAtFixedRate(task::run, delay, period, TimeUnit.SECONDS);
		
		TaskContext taskContext = new TaskContext();
		taskContext.setScheduledFuture(scheduledFuture);
		taskContext.setTask(task);
		
		scheduledFutureList.add(taskContext);
	}
	
	private static class TaskRequest {
		private Alert alert;
		private Control control;
		private Map<String, Object> mapParams;
		public Alert getAlert() {
			return alert;
		}
		public void setAlert(Alert alert) {
			this.alert = alert;
		}
		public Control getControl() {
			return control;
		}
		public void setControl(Control control) {
			this.control = control;
		}
		public Map<String, Object> getMapParams() {
			return mapParams;
		}
		public void setMapParams(Map<String, Object> mapParams) {
			this.mapParams = mapParams;
		}
	}
	
	private static class Task implements Runnable {
		
		private static final Logger log = LoggerFactory.getLogger(Task.class);
		
		private TaskRequest taskRequest;
		private long nextExecutionTime;
		private AlertResultRepository alertResultRepository;
		private CodStatusService codStatusService;

		@Override
		public void run() {
			AlertResult ar = new AlertResult();
			ar.setActive(true);
			Date date_ini = null;
			Date date_fin = null;
			try {
				log.info(StringUtils.concat("execute control: ", taskRequest.getAlert().getControl(), ", alert: ", taskRequest.getAlert().getName()));
				date_ini = new Date();
				Pair<Map<String, Object>, ControlResultStatus> result = taskRequest.getControl().execute(taskRequest.getMapParams());
				date_fin = new Date();
				log.info(StringUtils.concat("execute control ends: ", taskRequest.getAlert().getControl(), ", alert: ", taskRequest.getAlert().getName(), ". Result: ", result.getSecond().toString(), ", ", result.getFirst().toString()));
				
				ar.setAlert(taskRequest.getAlert());
				ar.setDateIni(date_ini);
				ar.setDateEnd(date_fin);
				ar.setVersion(taskRequest.getAlert().getVersion());
				ar.setParams(taskRequest.getAlert().getParams());
				ar.setStatusResult(codStatusService.getCodStatus(result.getSecond()));
				ar.setNeedsReview(result.getSecond().equals(ControlResultStatus.WARN) || result.getSecond().equals(ControlResultStatus.ERROR));
				ar.setResult(new ObjectMapper().writeValueAsString(result.getFirst()));
			} catch (Exception e) {
				log.error("error execute control", e);
				
				ar.setAlert(taskRequest.getAlert());
				ar.setDateIni(date_ini);
				ar.setDateEnd(date_fin);
				ar.setVersion(taskRequest.getAlert().getVersion());
				ar.setParams(taskRequest.getAlert().getParams());
				ar.setStatusResult(codStatusService.getCodStatus(ControlResultStatus.ERROR));
				ar.setNeedsReview(true);
				Map<String, Object> mapError = new HashMap<>();
				mapError.put("exception", throwableToString(e));
				try {
					ar.setResult(new ObjectMapper().writeValueAsString(mapError));
				} catch (Exception e2) {
					log.error("error with map to jsonb", e2);
				}
			}
			
			alertResultRepository.saveAndFlush(ar);
		}
		
		private String throwableToString(Throwable throwable) {
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PrintStream printStream = new PrintStream(outputStream);
            throwable.printStackTrace(printStream);
            return outputStream.toString();
		}

		public TaskRequest getTaskRequest() {
			return taskRequest;
		}

		public void setTaskRequest(TaskRequest taskRequest) {
			this.taskRequest = taskRequest;
		}

		@SuppressWarnings("unused")
		public long getNextExecutionTime() {
			return nextExecutionTime;
		}

		public void setNextExecutionTime(long nextExecutionTime) {
			this.nextExecutionTime = nextExecutionTime;
		}

		@SuppressWarnings("unused")
		public AlertResultRepository getAlertResultRepository() {
			return alertResultRepository;
		}

		public void setAlertResultRepository(AlertResultRepository alertResultRepository) {
			this.alertResultRepository = alertResultRepository;
		}

		@SuppressWarnings("unused")
		public CodStatusService getCodStatusService() {
			return codStatusService;
		}

		public void setCodStatusService(CodStatusService codStatusService) {
			this.codStatusService = codStatusService;
		}
	}
	
	private static class TaskContext {
		private Task task;
		private ScheduledFuture<?> scheduledFuture;
		
		public Task getTask() {
			return task;
		}
		public void setTask(Task task) {
			this.task = task;
		}
		public ScheduledFuture<?> getScheduledFuture() {
			return scheduledFuture;
		}
		public void setScheduledFuture(ScheduledFuture<?> scheduledFuture) {
			this.scheduledFuture = scheduledFuture;
		}
	}
}

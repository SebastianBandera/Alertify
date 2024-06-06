package app.watchful.service;

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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.watchful.control.Control;
import app.watchful.control.ControlResultStatus;
import app.watchful.control.common.StringUtils;
import app.watchful.entity.Alert;
import app.watchful.entity.AlertResult;
import app.watchful.entity.repositories.AlertResultRepository;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ThreadControl {

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
		queue.add(TaskRequest.builder().alert(alert).control(control).mapParams(mapParams).build());
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
				processTaskRequest(task);
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
		
		long delay = taskRequest.getAlert().getPeriodicity().getSeconds();
		
		Task task = Task.builder().taskRequest(taskRequest).nextExecutionTime(delay).alertResultRepository(alertResultRepository).codStatusService(codStatusService).build();
		
		ScheduledFuture<?> scheduledFuture = executorService.scheduleAtFixedRate(task::run, 0, delay, TimeUnit.SECONDS);
		
		scheduledFutureList.add(TaskContext.builder().task(task).scheduledFuture(scheduledFuture).build());
	}
	
	@Data
	@Builder
	private static class TaskRequest {
		private Alert alert;
		private Control control;
		private Map<String, Object> mapParams;
	}
	
	@Data
	@Builder
	@Slf4j
	private static class Task implements Runnable {
		private TaskRequest taskRequest;
		private long nextExecutionTime;
		private AlertResultRepository alertResultRepository;
		private CodStatusService codStatusService;

		@Override
		public void run() {
			AlertResult ar = new AlertResult();
			Date date_ini = null;
			Date date_fin = null;
			try {
				log.info(StringUtils.concat("execute control: ", taskRequest.getAlert().getControl(), ", alert: ", taskRequest.getAlert().getName()));
				date_ini = new Date();
				Pair<Map<String, Object>, ControlResultStatus> result = taskRequest.getControl().execute(taskRequest.getMapParams());
				date_fin = new Date();
				log.info(StringUtils.concat("execute control ends: ", taskRequest.getAlert().getControl(), ", alert: ", taskRequest.getAlert().getName(), ". Result: ", result.getSecond().toString(), ", ", result.getFirst().toString()));
				
				ar.setId_alert(taskRequest.getAlert());
				ar.setDate_ini(date_ini);
				ar.setDate_end(date_fin);
				ar.setParams(taskRequest.getAlert().getParams());
				ar.setStatus_result(codStatusService.getCodStatus(result.getSecond()));
				ar.setNeeds_review(result.getSecond().equals(ControlResultStatus.WARN) || result.getSecond().equals(ControlResultStatus.ERROR));
				ar.setResult(new ObjectMapper().writeValueAsString(result.getFirst()));
			} catch (Exception e) {
				e.printStackTrace();
				ar.setId_alert(taskRequest.getAlert());
				ar.setDate_ini(date_ini);
				ar.setDate_end(date_fin);
				ar.setParams(taskRequest.getAlert().getParams());
				ar.setStatus_result(codStatusService.getCodStatus(ControlResultStatus.ERROR));
				ar.setNeeds_review(true);
				Map<String, Object> mapError = new HashMap<>();
				mapError.put("exception", throwableToString(e));
				try {
					ar.setResult(new ObjectMapper().writeValueAsString(mapError));
				} catch (Exception e2) {
					e.printStackTrace();
					log.error("error with map to jsonb", e);
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
	}
	
	@Data
	@Builder
	private static class TaskContext {
		private Task task;
		private ScheduledFuture<?> scheduledFuture;
	}
}

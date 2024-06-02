CREATE TABLE alert.alerts (
	id serial NOT NULL,
	name text NOT NULL,
	"control" text NULL,
	params jsonb NULL,
	periodicity interval NULL,
	CONSTRAINT alerts_pk PRIMARY KEY (id),
	CONSTRAINT alerts_unique UNIQUE (name)
);


CREATE TABLE alert.cod_status (
	id serial NOT NULL,
	name text NOT NULL,
	CONSTRAINT cod_status_pk PRIMARY KEY (id),
	CONSTRAINT cod_status_unique UNIQUE (name)
);


CREATE TABLE alert.alerts_result (
	id serial NOT NULL,
	id_alert int4 not NULL,
	date_ini timestamp without time zone NOT NULL,
	date_end timestamp without time zone NULL,
	status_result int2 NOT NULL,
	params jsonb NULL,
	result jsonb NULL,
	needs_review bool not null,
	CONSTRAINT record_alert_pk PRIMARY KEY (id),
	CONSTRAINT record_alert_alerts_fk FOREIGN KEY (id_alert) REFERENCES alert.alerts(id),
	CONSTRAINT record_alert_cod_status_fk FOREIGN KEY (status_result) REFERENCES alert.cod_status(id)
);



INSERT INTO alert.cod_status("name") VALUES('success');
INSERT INTO alert.cod_status("name") VALUES('warn');
INSERT INTO alert.cod_status("name") VALUES('error');
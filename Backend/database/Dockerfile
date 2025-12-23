FROM postgres:17.2

COPY DDL.sql /DDL.sql

COPY script_db.sh /script_db.sh
RUN chmod +x /script_db.sh

RUN apt-get update && apt-get install -y dos2unix
RUN dos2unix /script_db.sh && mac2unix /script_db.sh

ENTRYPOINT ["/script_db.sh"]

FROM scylladb/scylla:1.3-rc3
MAINTAINER Yoav Kleinberger <yoav@scylladb.com>

EXPOSE 22

# fix sudoers
ADD etc/supervisord.conf /etc/supervisord.conf 
ADD scylla-service.sh /scylla-service.sh
ADD scylla-jmx-service.sh /scylla-jmx-service.sh

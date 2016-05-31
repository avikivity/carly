FROM centos:7
MAINTAINER Yoav Kleinberger <yoav@scylladb.com>

# ssh
RUN mkdir -p /root/.ssh
RUN chmod 700 /root/.ssh
ADD public_key_rsa /root/.ssh/authorized_keys
RUN chmod 600 /root/.ssh/authorized_keys
RUN yum -y install openssh-server iproute sudo
RUN ssh-keygen -f host_rsa
EXPOSE 22

# keep container running
ADD /stay.sh /stay.sh
RUN chmod +x /stay.sh
ADD /sudoers /etc/sudoers
RUN chmod 440 /etc/sudoers

# scylla ports
EXPOSE 10000 9042 9160 7000 7001 7199

CMD /stay.sh

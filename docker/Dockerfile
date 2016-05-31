FROM centos:7
MAINTAINER Yoav Kleinberger <yoav@scylladb.com>

RUN mkdir -p /root/.ssh
RUN chmod 700 /root/.ssh
ADD public_key_rsa /root/.ssh/authorized_keys
RUN chmod 600 /root/.ssh/authorized_keys
RUN yum -y install openssh-server iproute
RUN ssh-keygen -f host_rsa
ADD /start_ssh.sh /start_ssh.sh
RUN chmod +x /start_ssh.sh

EXPOSE 22
CMD /start_ssh.sh

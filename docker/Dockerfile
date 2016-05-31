FROM centos:7
MAINTAINER Yoav Kleinberger <yoav@scylladb.com>

# ssh
RUN mkdir -p /root/.ssh
RUN chmod 700 /root/.ssh
ADD public_key_rsa /root/.ssh/authorized_keys
RUN chmod 600 /root/.ssh/authorized_keys
RUN yum -y install openssh-server iproute
RUN ssh-keygen -f host_rsa
EXPOSE 22


# scylla
RUN curl http://downloads.scylladb.com/rpm/unstable/centos/master/latest/scylla.repo -o /etc/yum.repos.d/scylla.repo
RUN yum -y install epel-release
RUN yum -y clean expire-cache
RUN yum -y update
RUN yum -y remove boost-thread boost-system
RUN yum -y install scylla-server scylla-jmx scylla-tools hostname
RUN yum -y install sudo
RUN yum clean all
ADD start-scylla /start-scylla
RUN chown scylla /start-scylla
ADD bashrc /var/lib/scylla/.bashrc
RUN chown scylla /var/lib/scylla/.bashrc

# keep container running
ADD /go.sh /go.sh
RUN chmod +x /go.sh
ADD /sudoers /etc/sudoers
RUN chmod 440 /etc/sudoers

EXPOSE 10000 9042 9160 7000 7001
# go!

USER scylla
VOLUME /var/lib/scylla
CMD /go.sh

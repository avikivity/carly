all: scylladb-jepsen
HOME_BIN=~/bin
LEIN=$(HOME_BIN)/lein

home_bin:
	mkdir -p $(HOME_BIN)

leiningen: home_bin
	[[ -x $(LEIN) ]] || wget -O $(HOME_BIN)/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
	chmod +x $(LEIN)
	$(LEIN) version

scylladb-jepsen: leiningen
	git clone https://github.com/scylladb/jepsen --branch carly_oriented ~/scylladb-jepsen
	cd ~/scylladb-jepsen/jepsen ; $(LEIN) install

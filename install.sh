#! /bin/sh

cp run.sh /etc/init.d/
chmod 755 /etc/init.d/run.sh
update-rc.d run.sh defaults

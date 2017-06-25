#! /bin/sh
# /etc/init.d/run.sh 

### BEGIN INIT INFO
# Provides:          run.sh
# Required-Start:    $remote_fs $syslog
# Required-Stop:     $remote_fs $syslog
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: Simple script to start the program at boot
# Description:       A simple script which will start / stop the program a boot / shutdown.
### END INIT INFO

case "$1" in
  start)
    echo "Starting run.sh"
    # run application you want to start
    gpio -g mode 18 pwm
    gpio pwmc 1000
    gpio -g pwm 18 400
    fbcp &
    cd /home/pi/Desktop && \
    nice -n -10 \
      java -cp libs/*:./* \
        -XX:ThreadPriorityPolicy=1 \
        -XX:+UseThreadPriorities \
        -Dsun.net.http.allowRestrictedHeaders=true \
        -Dmonocle.input.0/0/0/0.minX=0 \
        -Dmonocle.input.0/0/0/0.maxX=240 \
        -Dmonocle.input.0/0/0/0.minY=0 \
        -Dmonocle.input.0/0/0/0.maxY=320 \
        -Dmonocle.input.0/0/0/0.flipXY=false \
        -Dconfig.file=configTest.properties \
        -Djava.util.logging.config.file=logging.properties \
        com.ben12.openhab.HabApplication &
    ;;
  stop)
    echo "Stopping run.sh"
    # kill application you want to stop
    killall java
    killall fbcp
    ;;
  *)
    echo "Usage: /etc/init.d/run.sh {start|stop}"
    exit 1
    ;;
esac


exit 0
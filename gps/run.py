#!/usr/bin/env python3
"""
Run nmea_hook.js against gnss_service via USB, no interactive REPL.
Optionally exposes a PTY at /tmp/gps0 for gpsd.

Usage:
  python3 run.py            # print NMEA to stdout
  python3 run.py --pty      # also expose PTY at /tmp/gps0
  python3 run.py [pid]      # explicit PID
"""

import sys
import os
import pty
import tty
import signal
import frida

SCRIPT_PATH  = 'nmea_hook.js'
EXTRA_SCRIPTS = ['nmea-stop.js']
TARGET_NAME = 'android.hardware.gnss@2.1-service-qti'
PTY_LINK    = '/tmp/gps0'

pty_master_fd = None

def write_to_pty(line):
    """Write a line to the PTY master, silently drop if no reader."""
    global pty_master_fd
    if pty_master_fd is None:
        return
    try:
        n = os.write(pty_master_fd, (line + '\r\n').encode())
        print(f'[pty] wrote {n} bytes: {line[:40]}', file=sys.stderr, flush=True)
    except OSError as e:
        print(f'[pty write error] {e}', file=sys.stderr, flush=True)

def on_message(message, _data):
    if message['type'] == 'send':
        line = message['payload']
        print(line, flush=True)
        write_to_pty(line)
    elif message['type'] == 'error':
        print('[frida error]', message['description'], file=sys.stderr, flush=True)

pty_slave_fd = None

def setup_pty():
    global pty_master_fd, pty_slave_fd
    master_fd, slave_fd = pty.openpty()
    slave_path = os.ttyname(slave_fd)

    # Raw mode: disable line discipline so GPS data passes through unmodified
    tty.setraw(slave_fd)

    # Keep slave open so writes to master never get EIO
    pty_slave_fd = slave_fd

    # Stable symlink
    try:
        os.unlink(PTY_LINK)
    except FileNotFoundError:
        pass
    os.symlink(slave_path, PTY_LINK)

    pty_master_fd = master_fd
    print(f'[*] PTY slave: {slave_path}  →  {PTY_LINK}', file=sys.stderr)

def main():
    use_pty = '--pty' in sys.argv
    args = [a for a in sys.argv[1:] if a != '--pty']

    if use_pty:
        setup_pty()

    device = frida.get_usb_device()

    if args:
        target = int(args[0])
    else:
        procs = device.enumerate_processes()
        match = next((p for p in procs if TARGET_NAME in p.name), None)
        if not match:
            sys.exit(f'[!] Process "{TARGET_NAME}" not found')
        target = match.pid
        print(f'[*] Attaching to {match.name} (PID {target})', file=sys.stderr)

    session = device.attach(target)

    with open(SCRIPT_PATH) as f:
        source = f.read()

    script = session.create_script(source)
    script.on('message', on_message)
    script.load()

    for path in EXTRA_SCRIPTS:
        with open(path) as f:
            extra_script = session.create_script(f.read())
        extra_script.load()

    def shutdown(*_):
        session.detach()
        if pty_master_fd is not None:
            os.close(pty_master_fd)
        if pty_slave_fd is not None:
            os.close(pty_slave_fd)
        try:
            os.unlink(PTY_LINK)
        except FileNotFoundError:
            pass
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.pause()

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""WSL 内访问 Windows 上 kubo API 的 stdio 桥（只在 WSL 里用）。

背景：本机 WSL2 是 NAT 模式，WSL 访问不到 Windows 的 127.0.0.1；kubo 只有 Windows 版 ipfs.exe，
API 只监听 Windows 回环。这里不改防火墙、不让 kubo 监听其他网卡，而是用 WSL interop：
WSL 侧在 127.0.0.1:<port> 监听，每个连接启动一个 Windows python.exe，由它连 Windows 的 127.0.0.1:<port>，
两边经 stdin/stdout 原样转发字节。只有本机 WSL 内的进程能连到这个端口。

用法：python3 wsl-bridge.py <port> <windows-python.exe 的 WSL 路径>
只用 Python 标准库。
"""
import socket
import subprocess
import sys
import threading

# 在 Windows 侧执行：连 kubo，stdin -> socket，socket -> stdout
WIN_SIDE = r"""
import socket, sys, threading
s = socket.create_connection(('127.0.0.1', int(sys.argv[1])))
def up():
    src = sys.stdin.buffer
    while True:
        b = src.read1(65536)
        if not b:
            break
        s.sendall(b)
    try:
        s.shutdown(socket.SHUT_WR)
    except OSError:
        pass
threading.Thread(target=up, daemon=True).start()
out = sys.stdout.buffer
while True:
    b = s.recv(65536)
    if not b:
        break
    out.write(b)
    out.flush()
"""


def pump_socket_to_pipe(conn, pipe):
    try:
        while True:
            b = conn.recv(65536)
            if not b:
                break
            pipe.write(b)
            pipe.flush()
    except OSError:
        pass
    finally:
        try:
            pipe.close()
        except OSError:
            pass


def pump_pipe_to_socket(pipe, conn):
    try:
        while True:
            b = pipe.read1(65536) if hasattr(pipe, "read1") else pipe.read(65536)
            if not b:
                break
            conn.sendall(b)
    except OSError:
        pass
    finally:
        try:
            conn.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(conn, port, win_python):
    proc = subprocess.Popen([win_python, "-u", "-c", WIN_SIDE, str(port)],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    t = threading.Thread(target=pump_socket_to_pipe, args=(conn, proc.stdin), daemon=True)
    t.start()
    pump_pipe_to_socket(proc.stdout, conn)
    t.join(timeout=5)
    conn.close()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()


def main():
    port = int(sys.argv[1])
    win_python = sys.argv[2]
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(64)
    print(f"[ipfs-bridge] WSL 127.0.0.1:{port} -> Windows 127.0.0.1:{port}（经 {win_python}）", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn, port, win_python), daemon=True).start()


if __name__ == "__main__":
    main()

/*
Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package libexclavecore

import (
	"bufio"
	"encoding/binary"
	"encoding/hex"
	"net"
	"os"
	"slices"
	"strconv"
	"strings"
	"sync"
	"unsafe"

	"golang.org/x/sys/unix"
)

var (
	once             sync.Once
	netIndexOfLocal  = -1
	netIndexOfRemote = -1
	netIndexOfUid    = -1
	netIndexOfInode  = -1
	nativeEndian     binary.ByteOrder
)

func querySocketUidFromProcFs(ipProto int32, srcIP net.IP, srcPort uint16, dstIP net.IP, dstPort uint16) int32 {
	once.Do(initOnce)
	if netIndexOfLocal < 0 || netIndexOfRemote < 0 || netIndexOfUid < 0 || netIndexOfInode < 0 {
		return -1
	}
	var path string
	switch ipProto {
	case unix.IPPROTO_TCP:
		path = "/proc/net/tcp"
	case unix.IPPROTO_UDP:
		path = "/proc/net/udp"
	}
	if srcIPv4, dstIPv4 := srcIP.To4(), dstIP.To4(); srcIPv4 != nil && dstIPv4 != nil {
		if uid := querySocketUidFromProcFsInternal(path, ipProto, srcIPv4, srcPort, dstIPv4, dstPort); uid != -1 {
			return uid
		}
		// java.net sockets on Android are always dual-stack AF_INET6, so their IPv4 connections are only listed in the IPv6 table.
	}
	return querySocketUidFromProcFsInternal(path+"6", ipProto, srcIP, srcPort, dstIP, dstPort)
}

func querySocketUidFromProcFsInternal(path string, ipProto int32, srcIP net.IP, srcPort uint16, dstIP net.IP, dstPort uint16) int32 {
	var srcPortBytes [2]byte
	binary.BigEndian.PutUint16(srcPortBytes[:], srcPort)
	source := hex.EncodeToString(nativeEndianIP(srcIP)) + ":" + hex.EncodeToString(srcPortBytes[:])
	var dstPortBytes [2]byte
	binary.BigEndian.PutUint16(dstPortBytes[:], dstPort)
	dest := hex.EncodeToString(nativeEndianIP(dstIP)) + ":" + hex.EncodeToString(dstPortBytes[:])
	var zeroAddrs []string
	if ipProto == unix.IPPROTO_UDP {
		if len(srcIP) == net.IPv4len {
			zeroAddrs = append(zeroAddrs, hex.EncodeToString(nativeEndianIP(net.IPv4zero))+":"+hex.EncodeToString(srcPortBytes[:]))
		} else {
			zeroAddrs = append(zeroAddrs, hex.EncodeToString(nativeEndianIP(net.IPv6zero))+":"+hex.EncodeToString(srcPortBytes[:]))
			if srcIP.To4() != nil {
				zeroAddrs = append(zeroAddrs, hex.EncodeToString(nativeEndianIP(net.IPv4zero.To16()))+":"+hex.EncodeToString(srcPortBytes[:]))
			}
		}
	}
	file, err := os.Open(path)
	if err != nil {
		return -1
	}
	defer file.Close()
	reader := bufio.NewReader(file)
	var bestRank int
	var uid int32 = -1
	for {
		row, _, err := reader.ReadLine()
		if err != nil {
			return uid
		}
		fields := strings.Fields(string(row))
		if len(fields) <= netIndexOfLocal || len(fields) <= netIndexOfUid || len(fields) <= netIndexOfRemote || len(fields) <= netIndexOfInode {
			continue
		}
		local, remote := fields[netIndexOfLocal], fields[netIndexOfRemote]
		var rank int
		switch {
		case strings.EqualFold(source, local) && strings.EqualFold(dest, remote):
			rank = 3
		case strings.EqualFold(source, local):
			rank = 2
		case slices.ContainsFunc(zeroAddrs, func(zeroAddr string) bool {
			return strings.EqualFold(source, zeroAddr)
		}):
			rank = 1
		default:
			continue
		}
		if rank <= bestRank {
			continue
		}
		u, err := strconv.Atoi(fields[netIndexOfUid])
		if err != nil {
			continue
		}
		if rank == 3 {
			return int32(u)
		}
		bestRank = rank
		uid = int32(u)
	}
}

func nativeEndianIP(ip net.IP) []byte {
	result := make([]byte, len(ip))
	for i := 0; i < len(ip); i += 4 {
		value := binary.BigEndian.Uint32(ip[i:])
		nativeEndian.PutUint32(result[i:], value)
	}
	return result
}

func initOnce() {
	file, err := os.Open("/proc/net/tcp")
	if err != nil {
		return
	}
	defer file.Close()
	reader := bufio.NewReader(file)
	header, _, err := reader.ReadLine()
	if err != nil {
		return
	}
	columns := strings.Fields(string(header))
	var txQueue, rxQueue, tr, tmWhen bool
	for idx, column := range columns {
		offset := 0
		if txQueue && rxQueue {
			offset--
		}
		if tr && tmWhen {
			offset--
		}
		switch column {
		case "tx_queue":
			txQueue = true
		case "rx_queue":
			rxQueue = true
		case "tr":
			tr = true
		case "tm->when":
			tmWhen = true
		case "local_address":
			netIndexOfLocal = idx + offset
		case "rem_address":
			netIndexOfRemote = idx + offset
		case "uid":
			netIndexOfUid = idx + offset
		case "inode":
			netIndexOfInode = idx + offset
		}
	}
	var x uint32 = 0x01020304
	if *(*byte)(unsafe.Pointer(&x)) == 0x01 {
		nativeEndian = binary.BigEndian
	} else {
		nativeEndian = binary.LittleEndian
	}
}

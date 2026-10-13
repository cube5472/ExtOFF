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
	"context"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/exclavenetwork/exclave-core/v5/common/net"
	"github.com/exclavenetwork/exclave-core/v5/common/session"
)

// NextClave: measure the delay the way v2rayNG does (see AndroidLibXrayLite
// measureInstDelay): two GET attempts over one shared transport with
// keep-alives ENABLED. The first attempt pays the full cold-connection cost
// (TCP to the server + proxy protocol handshake + TLS through the tunnel);
// the second attempt reuses the already established tunnel connection and
// measures nearly pure round-trip time. The minimum of the successful
// attempts is reported. The old single-shot HEAD with DisableKeepAlives
// produced values inflated 3-5x compared to v2rayNG and other clients,
// because it always included every handshake in the reported number.
func UrlTest(instance *V2RayInstance, inbound string, link string, timeout int32) (int32, error) {
	transport := &http.Transport{
		TLSHandshakeTimeout: time.Duration(timeout) * time.Millisecond,
		DisableKeepAlives:   false,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dest, err := net.ParseDestination(fmt.Sprintf("%s:%s", network, addr))
			if err != nil {
				return nil, err
			}
			if inbound != "" {
				ctx = session.ContextWithInbound(ctx, &session.Inbound{Tag: inbound})
			}
			return instance.dial(ctx, dest)
		},
	}
	defer transport.CloseIdleConnections()
	httpClient := &http.Client{
		Transport: transport,
		Timeout:   time.Duration(timeout) * time.Millisecond,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	var minDuration int32
	var success bool
	var lastErr error
	const attempts = 2
	for i := 0; i < attempts; i++ {
		req, err := http.NewRequest("GET", link, nil)
		if err != nil {
			return 0, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
		start := time.Now()
		resp, err := httpClient.Do(req.WithContext(ctx))
		cancel()
		if err != nil {
			lastErr = err
			continue
		}
		_, err = io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
			lastErr = fmt.Errorf("invalid status: %s", resp.Status)
			continue
		}
		if err != nil {
			lastErr = err
			continue
		}
		duration := int32(time.Since(start).Milliseconds())
		if !success || duration < minDuration {
			minDuration = duration
		}
		success = true
	}
	if !success {
		return 0, lastErr
	}
	return minDuration, nil
}

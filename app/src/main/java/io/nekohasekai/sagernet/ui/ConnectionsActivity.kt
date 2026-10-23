/******************************************************************************
 *                                                                            *
 * NextClave: live connections viewer ("Requests")                            *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.ConnectionsMonitor
import io.nekohasekai.sagernet.bg.ConnectionsMonitor.ConnEntry
import io.nekohasekai.sagernet.databinding.LayoutConnItemBinding
import io.nekohasekai.sagernet.databinding.LayoutConnectionsBinding
import kotlinx.coroutines.launch

class ConnectionsActivity : ThemedActivity() {

    private lateinit var binding: LayoutConnectionsBinding
    private lateinit var adapter: ConnectionsAdapter
    private var query: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val timeTick = object : Runnable {
        override fun run() {
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.connections)
            setDisplayHomeAsUpEnabled(true)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.connectionsHolder) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }

        adapter = ConnectionsAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        // Coalesce bursts of log lines into at most 4 UI refreshes per second.
        val flushRunnable = Runnable {
            adapter.submit(filter(ConnectionsMonitor.connections.value))
            updateEmpty()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConnectionsMonitor.connections.collect {
                    handler.removeCallbacks(flushRunnable)
                    handler.postDelayed(flushRunnable, 250)
                }
            }
        }
    }

    private fun filter(list: List<ConnEntry>): List<ConnEntry> {
        val q = query.trim()
        if (q.isEmpty()) return list
        val lower = q.lowercase()
        return list.filter { entry ->
            entry.dstAddr.lowercase().contains(lower) ||
                entry.dstPort.toString().contains(lower) ||
                (entry.domain?.lowercase()?.contains(lower) ?: false) ||
                (entry.pkg?.lowercase()?.contains(lower) ?: false) ||
                (entry.outbound?.lowercase()?.contains(lower) ?: false)
        }
    }

    private fun updateEmpty() {
        val empty = adapter.itemCount == 0
        binding.empty.isVisible = empty
        binding.recycler.isVisible = !empty
        if (empty) {
            // NextClave: transparent channel diagnostics — if the list stays
            // empty, the screen itself shows whether the core wrote any log
            // lines and how many were recognized.
            binding.emptyDiag.text =
                getString(R.string.connections_diag, ConnectionsMonitor.debugStats())
        }
    }

    override fun onStart() {
        super.onStart()
        ConnectionsMonitor.start()
    }

    override fun onStop() {
        ConnectionsMonitor.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        handler.post(timeTick)
    }

    override fun onPause() {
        handler.removeCallbacks(timeTick)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_connections, menu)
        val searchView = menu.findItem(R.id.action_conn_search)?.actionView as? androidx.appcompat.widget.SearchView
        searchView?.maxWidth = Int.MAX_VALUE
        searchView?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(query: String?): Boolean {
                this@ConnectionsActivity.query = query ?: ""
                adapter.submit(filter(ConnectionsMonitor.connections.value))
                updateEmpty()
                return false
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_conn_clear -> {
                ConnectionsMonitor.clear()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private class ConnectionsAdapter :
        RecyclerView.Adapter<ConnectionsAdapter.Holder>() {

        private var items: List<ConnEntry> = emptyList()

        fun submit(list: List<ConnEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding =
                LayoutConnItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        class Holder(val binding: LayoutConnItemBinding) : RecyclerView.ViewHolder(binding.root) {

            fun bind(entry: ConnEntry) {
                val context = binding.root.context
                val host = entry.domain ?: entry.dstAddr
                binding.destination.text =
                    "${entry.proto.lowercase()}://$host:${entry.dstPort}"

                val age = (System.currentTimeMillis() - entry.firstSeen) / 1000
                binding.whenText.text = if (!entry.live) {
                    context.getString(R.string.connections_closed)
                } else when {
                    age < 10 -> context.getString(R.string.connections_just_now)
                    age < 60 -> context.getString(R.string.connections_seconds_ago, age.toInt())
                    age < 3600 -> context.getString(R.string.connections_minutes_ago, (age / 60).toInt())
                    else -> context.getString(R.string.connections_hours_ago, (age / 3600).toInt())
                }

                binding.appLine.text = when {
                    entry.pkg != null -> entry.pkg
                    entry.uid >= 0 -> context.getString(R.string.connections_unknown_app, entry.uid)
                    else -> context.getString(R.string.connections_source, entry.src)
                }

                val outbound = entry.outbound
                if (outbound != null && outbound != "dns-out") {
                    binding.chipOutbound.isVisible = true
                    // NextClave: prefer the resolved node display name over
                    // the raw outbound tag (proxy-global-1505 → node name).
                    binding.chipOutbound.text = entry.nodeName ?: outbound
                } else if (entry.isDefault) {
                    binding.chipOutbound.isVisible = true
                    binding.chipOutbound.text = context.getString(R.string.connections_chip_default)
                } else {
                    binding.chipOutbound.isVisible = false
                }

                binding.chipProto.text = entry.proto.uppercase()
                binding.chipDns.isVisible = entry.isDns || outbound == "dns-out"
                binding.chipBlocked.isVisible = entry.blocked
            }

        }

    }

}

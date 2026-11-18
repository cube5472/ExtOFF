/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.BalancerBean

// NextClave: multi-group balancer helpers. One source of truth for "which
// profiles belong to this balancer", shared by ConfigBuilder (config build)
// and ProxyInstance.workingMemberId() (the url-test patch). A balancer of
// TYPE_GROUP aggregates any number of groups (subscriptions): profiles are
// resolved per group in the order the groups were selected, then by each
// group's userOrder; the same profile cannot enter twice. Both name filters
// keep their original semantics:
//   nameFilter  — EXCLUDE regex (profile dropped when it matches);
//   nameFilter1 — INCLUDE regex (profile kept only when it matches).
// Single-group balancers resolve to exactly the same members as before.

fun balancerNameAllowed(bean: BalancerBean, name: String): Boolean {
    if (bean.nameFilter.isNotEmpty() && Regex(bean.nameFilter).containsMatchIn(name)) return false
    if (bean.nameFilter1.isNotEmpty() && !Regex(bean.nameFilter1).containsMatchIn(name)) return false
    return true
}

// All members of the balancer, ordered and de-duplicated.
fun BalancerBean.resolveBalancerMembers(): List<ProxyEntity> {
    return when (type) {
        BalancerBean.TYPE_LIST -> {
            val entities = SagerDatabase.proxyDao.getEntities(proxies).associateBy { it.id }
            proxies.mapNotNull { entities[it] }
        }
        else -> {
            val seen = HashSet<Long>()
            val members = ArrayList<ProxyEntity>()
            for (groupId in effectiveGroupIds()) {
                for (entity in SagerDatabase.proxyDao.getByGroup(groupId)) {
                    if (!seen.add(entity.id)) continue
                    if (balancerNameAllowed(this, entity.requireBean().name)) {
                        members.add(entity)
                    }
                }
            }
            members
        }
    }
}

// Same members grouped by their source group, used by buildChain() to apply
// each group's own front/landing proxy to that group's members only.
fun BalancerBean.resolveBalancerMembersByGroup(): Map<Long, List<ProxyEntity>> {
    val byGroup = LinkedHashMap<Long, List<ProxyEntity>>()
    for (groupId in effectiveGroupIds()) {
        byGroup[groupId] = SagerDatabase.proxyDao.getByGroup(groupId)
            .filter { balancerNameAllowed(this, it.requireBean().name) }
    }
    return byGroup
}

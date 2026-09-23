package org.qo.services.fallenServices

object FallenTeamAllocator {
	fun allocate(registrations: List<FallenRegistration>): Map<String, FallenTeam> {
		if (registrations.isEmpty()) return emptyMap()
		val preferenceCounts = FallenTeam.entries.associateWith { team -> registrations.count { it.expectedTeam == team } }
		val capacities = FallenTeam.entries.associateWith { registrations.size / FallenTeam.entries.size }.toMutableMap()
		FallenTeam.entries
			.sortedWith(compareByDescending<FallenTeam> { preferenceCounts.getValue(it) }.thenBy { it.name })
			.take(registrations.size % FallenTeam.entries.size)
			.forEach { capacities[it] = capacities.getValue(it) + 1 }

		val assignments = linkedMapOf<String, FallenTeam>()
		val overflow = mutableListOf<FallenRegistration>()
		for (team in FallenTeam.entries) {
			val preferred = registrations
				.filter { it.expectedTeam == team }
				.sortedWith(compareBy<FallenRegistration> { it.selectedAt }.thenBy { it.username })
			val retained = preferred.take(capacities.getValue(team))
			retained.forEach { assignments[it.username] = team }
			capacities[team] = capacities.getValue(team) - retained.size
			overflow += preferred.drop(retained.size)
		}
		overflow.sortedWith(compareBy<FallenRegistration> { it.selectedAt }.thenBy { it.username }).forEach { registration ->
			val team = FallenTeam.entries
				.filter { capacities.getValue(it) > 0 }
				.maxWithOrNull(compareBy<FallenTeam> { capacities.getValue(it) }.thenByDescending { it.name })
				?: error("No Fallen team capacity remains")
			assignments[registration.username] = team
			capacities[team] = capacities.getValue(team) - 1
		}
		return assignments
	}

	fun leastPopulatedTeam(assignments: Collection<FallenTeam>): FallenTeam {
		val counts = FallenTeam.entries.associateWith { team -> assignments.count { it == team } }
		return FallenTeam.entries.minWith(compareBy<FallenTeam> { counts.getValue(it) }.thenBy { it.name })
	}
}

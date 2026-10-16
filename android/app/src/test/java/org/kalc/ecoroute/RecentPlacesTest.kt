package org.kalc.ecoroute

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class RecentPlacesTest {
    private val a = Place("a",46.0,14.0,"Ljubljana, Slovenia","Ljubljana","Slovenia","SI","city")
    private val b = Place("b",45.515,13.59,"Šolska pot, Tolmin, 5220, Slovenia","Šolska pot","Tolmin, 5220, Slovenia","SI","street")

    @Test fun selectedPlacesAreUniqueRecentFirstAndBounded() {
        var history = emptyList<Place>()
        for (i in 0..30) history = RecentPlaces.remember(history,a.copy(id="$i",label="Address $i",title="Address $i"))
        assertEquals(20,history.size); assertEquals("30",history.first().id)
        val selected = history[5]
        history = RecentPlaces.remember(history,selected)
        assertEquals(selected,history.first()); assertEquals(20,history.size)
        assertEquals(1,history.count { it.id == selected.id })
    }
    @Test fun duplicateProviderIdsAtTheSameAddressCollapseButDifferentCitiesRemain() {
        val history = RecentPlaces.remember(listOf(b),b.copy(id="new",lat=b.lat+.00001))
        assertEquals(1,history.size); assertEquals("new",history.single().id)
        assertEquals(2,RecentPlaces.remember(history,b.copy(id="far",lat=46.0)).size)
    }
    @Test fun completeCoordinatesAndDisplayDetailsSurvivePersistence() {
        assertEquals(listOf(b,a),RecentPlaces.read(RecentPlaces.encode(listOf(b,a))))
    }
    @Test fun partialWordsCaseAndAccentsFilterHistoryWithoutUnrelatedMatches() {
        val history = listOf(b,a)
        for (query in listOf("sol", "SOLSKA", "tolmin sol", "522", "  Šolska   pot  ")) {
            assertEquals(listOf(b),RecentPlaces.matching(history,query))
        }
        assertEquals(history,RecentPlaces.matching(history,""))
        assertEquals(emptyList<Place>(),RecentPlaces.matching(history,"unknown"))
    }
    @Test fun recentSuggestionsStayAboveOnlineResultsAndVisibleDuplicatesAreRemoved() {
        val groups = RecentPlaces.suggestions(listOf(b,a),AddressState(text="sol",
            suggestions=listOf(b.copy(id="other-id"),b.copy(id="new-road",label="Solska cesta",title="Solska cesta"))))
        assertEquals(listOf(b),groups.recent)
        assertEquals(listOf("new-road"),groups.online.map { it.id })
        assertEquals(RecentPlaces.Suggestions(emptyList(),emptyList()),
            RecentPlaces.suggestions(listOf(b),AddressState(text=b.label,selected=b)))
        assertEquals(listOf(b,a),RecentPlaces.suggestions(listOf(b,a),
            AddressState(text=b.label,selected=b),showWhenSelected=true).recent)
    }
    @Test fun blankAndShortQueriesHaveLocalSuggestionsWithFiniteDisplayLimits() {
        val history = (0..19).map { a.copy(id="$it",title="Place $it",label="Place $it") }
        assertEquals(5,RecentPlaces.suggestions(history,AddressState()).recent.size)
        assertEquals(3,RecentPlaces.suggestions(history,AddressState(text="p")).recent.size)
    }
    @Test fun malformedRecordsAndLivePositionsAreNotRestoredAsHistory() {
        assertTrue(RecentPlaces.read("not-json").isEmpty())
        val entries = JSONArray(RecentPlaces.encode(listOf(b))).put("broken")
        assertEquals(listOf(b),RecentPlaces.read(entries.toString()))
        for (place in listOf(a.copy(kind="current_location"),a.copy(lat=91.0),a.copy(lon=Double.NaN),a.copy(id=""))) {
            assertEquals(listOf(b),RecentPlaces.remember(listOf(b),place))
        }
    }
}

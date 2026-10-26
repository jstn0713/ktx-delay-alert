package io.github.jstn0713.ktxdelay.core

/** KTX 정차역(좌표 포함). 네이버 길찾기(transit) 호출에 좌표가 필요하다. */
data class Station(val name: String, val lat: Double, val lng: Double)

object Stations {
    /** 주요 KTX 역 (이름, 위도, 경도) — 네이버 역 데이터 기준 */
    val LIST: List<Station> = listOf(
        Station("서울", 37.554073, 126.9707021),
        Station("용산", 37.5300508, 126.9650777),
        Station("영등포", 37.5152412, 126.9071024),
        Station("광명", 37.416534, 126.8848035),
        Station("수원", 37.2660447, 126.9997956),
        Station("행신", 37.6121179, 126.8343245),
        Station("청량리", 37.58103, 127.0487122),
        Station("상봉", 37.5970913, 127.0858735),
        Station("수서", 37.4855438, 127.1043773),
        Station("동탄", 37.1998621, 127.0954914),
        Station("평택지제", 37.0188014, 127.0698621),
        Station("천안아산", 36.794568, 127.104369),
        Station("오송", 36.6200524, 127.3275639),
        Station("대전", 36.33235, 127.4342),
        Station("서대전", 36.3225755, 127.4036881),
        Station("공주", 36.3323631, 127.0967682),
        Station("계룡", 36.2728953, 127.265287),
        Station("논산", 36.2072692, 127.0924043),
        Station("김천구미", 36.1133434, 128.1809962),
        Station("동대구", 35.8796734, 128.6284589),
        Station("서대구", 35.8815475, 128.5399747),
        Station("경산", 35.8192496, 128.7277131),
        Station("밀양", 35.474472, 128.771162),
        Station("구포", 35.2054351, 128.9971252),
        Station("부산", 35.1151869, 129.0416007),
        Station("울산(통도사)", 35.5514086, 129.1379724),
        Station("경주", 35.7983496, 129.1389373),
        Station("포항", 36.0716843, 129.3419644),
        Station("태화강", 35.5386687, 129.3533533),
        Station("마산", 35.2361035, 128.5771588),
        Station("창원", 35.2573648, 128.6067776),
        Station("창원중앙", 35.2423233, 128.7013406),
        Station("진주", 35.1507068, 128.1179095),
        Station("익산", 35.940496, 126.945758),
        Station("김제", 35.7924479, 126.9028903),
        Station("정읍", 35.5755323, 126.8423939),
        Station("장성", 35.3, 126.780175),
        Station("광주송정", 35.137757, 126.79095),
        Station("나주", 35.0142925, 126.7170689),
        Station("목포", 34.7911599, 126.386563),
        Station("전주", 35.8498404, 127.1618183),
        Station("남원", 35.411255, 127.361335),
        Station("순천", 34.9457781, 127.5032322),
        Station("여수EXPO", 34.753011, 127.748926),
        Station("서원주", 37.3490322, 127.8399712),
        Station("원주", 37.3159473, 127.9218976),
        Station("만종", 37.3536649, 127.8930952),
        Station("평창", 37.5623575, 128.4298575),
        Station("진부(오대산)", 37.6428022, 128.5744664),
        Station("강릉", 37.7645689, 128.8995705),
        Station("정동진", 37.6914429, 129.0326875),
        Station("동해", 37.4980743, 129.1237222),
        Station("안동", 36.5748199, 128.6747718),
        Station("영주", 36.8108607, 128.6249989),
        Station("제천", 37.1278717, 128.2054534),
        Station("인천공항T1", 37.4468263, 126.4515453),
        Station("인천공항T2", 37.4684717, 126.4324851),
    )

    private val BY_NAME = LIST.associateBy { normStation(it.name) }

    fun find(name: String): Station? = BY_NAME[normStation(name)]

    /** 이름 목록 (자동완성용) */
    val KTX: List<String> = LIST.map { it.name }

    fun suggest(input: String, limit: Int = 8): List<String> {
        val q = normStation(input)
        if (q.isEmpty()) return listOf("천안아산", "수서", "서울", "오송", "대전", "동대구", "부산", "광명").take(limit)
        val starts = KTX.filter { normStation(it).startsWith(q) }
        val contains = KTX.filter { !normStation(it).startsWith(q) && normStation(it).contains(q) }
        return (starts + contains).filter { normStation(it) != q }.take(limit)
    }
}

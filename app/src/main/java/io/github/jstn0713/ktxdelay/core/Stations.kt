package io.github.jstn0713.ktxdelay.core

/** 역 이름 입력할 때 보여줄 추천 목록 (KTX가 서는 주요 역). 목록에 없는 역도 직접 입력하면 된다. */
object Stations {
    val KTX = listOf(
        "서울", "용산", "수서", "광명", "영등포", "수원", "행신", "청량리", "상봉",
        "동탄", "평택지제", "천안아산", "오송", "대전", "서대전", "계룡", "논산", "공주",
        "김천구미", "서대구", "동대구", "대구", "신경주", "경주", "울산(통도사)", "태화강", "부산", "구포", "부전",
        "포항", "밀양", "진영", "창원중앙", "창원", "마산", "진주",
        "익산", "김제", "정읍", "장성", "광주송정", "나주", "목포",
        "전주", "남원", "곡성", "구례구", "순천", "여천", "여수EXPO",
        "양평", "서원주", "만종", "횡성", "둔내", "평창", "진부(오대산)", "강릉", "정동진", "묵호", "동해",
        "원주", "제천", "단양", "풍기", "영주", "안동", "의성", "영천",
    )

    fun suggest(input: String, limit: Int = 8): List<String> {
        val q = input.trim()
        if (q.isEmpty()) return listOf("천안아산", "수서", "서울", "오송", "대전", "동대구", "부산", "광명").take(limit)
        val starts = KTX.filter { it.startsWith(q) }
        val contains = KTX.filter { !it.startsWith(q) && it.contains(q) }
        return (starts + contains).filter { it != q }.take(limit)
    }
}

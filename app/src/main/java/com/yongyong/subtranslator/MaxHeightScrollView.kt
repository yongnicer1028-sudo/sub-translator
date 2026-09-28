package com.yongyong.subtranslator

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/**
 * 자막이 조금만 있을 때는 내용 크기만큼만 작게 보이고, 자막이 쌓여서 [maxHeightPx]보다
 * 커질 때만 그때 가서 [maxHeightPx]에서 멈추고 스크롤이 생기는 ScrollView예요.
 *
 * ⚠ v16: 원래(v10~v15)는 이 자막 영역(captionScroll)의 높이를 매번 고정된 숫자
 * (기본 220dp, 손잡이/[크기] 버튼으로 조절한 값)로 "정확히 그 크기"로 딱 정해서
 * 썼어요. 그런데 이러면 "듣는 중…"처럼 한 줄짜리 짧은 내용만 있을 때도 상자가
 * 억지로 그 고정 크기(예: 220dp)만큼 크게 늘어나서 글자 아래로 빈 공간이 크게
 * 남았고, 그 빈 공간 옆에도 크기 조절 손잡이(반투명 흰 띠)가 그대로 길게 이어져서
 * "손잡이가 글자 상자랑 따로 떨어진 별개의 조각처럼 보인다"는 문제가 있었어요(v15에서
 * 손잡이 모서리를 둥글게 맞춘 것만으론 이 문제가 해결되지 않았던 이유가 바로 이거예요
 * - 모서리 모양이 문제가 아니라, 내용은 적은데 상자만 억지로 커서 생기는 빈 공간이
 * 진짜 원인이었어요).
 *
 * 이제는 "최대 높이(maxHeightPx)"만 정해두고, 실제 자막 내용이 그보다 작으면 내용
 * 크기만큼만 작게 보이고, 내용이 그보다 커지면 그때 가서 maxHeightPx에서 멈추고
 * 안에서 스크롤되게 했어요. 손잡이/[크기] 버튼으로 조절하는 값은 이제 "고정 크기"가
 * 아니라 "이 이상은 안 커지고 스크롤되는 한계선"이 돼요.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** -1이면 제한 없음(평범한 ScrollView와 똑같이 동작해요). */
    var maxHeightPx: Int = -1
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var heightSpec = heightMeasureSpec
        val limit = maxHeightPx
        if (limit >= 0) {
            val mode = View.MeasureSpec.getMode(heightMeasureSpec)
            val size = View.MeasureSpec.getSize(heightMeasureSpec)
            // 부모가 이미 더 좁은 공간을 준 상태라면 그 값을 존중하고, 아니라면
            // 우리가 정한 최대 높이로 제한해요 - 어느 쪽이든 "그 이상은 넘지 않되,
            // 내용이 더 작으면 작은 대로" 보여줄 수 있게 AT_MOST로 넘겨요.
            val cappedSize = if (mode == View.MeasureSpec.UNSPECIFIED) limit else minOf(size, limit)
            heightSpec = View.MeasureSpec.makeMeasureSpec(cappedSize, View.MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, heightSpec)
    }
}

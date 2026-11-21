use crate::models::TripResult;
use crate::round_half_up_i32;

/// 주행이 끝난 뒤의 코칭.
pub struct Coaching;

impl Coaching {
    pub const RECENT_TRIPS: usize = 5;

    /// 이번 에코 점수가 최근 5번 평균보다 몇 점 높은지. 앞선 기록이 2번 미만이면 None.
    pub fn eco_delta(result: &TripResult, history: &[TripResult]) -> Option<i32> {
        let start = history.len().saturating_sub(Self::RECENT_TRIPS);
        let recent = &history[start..];
        if recent.len() < 2 {
            return None;
        }
        let avg = recent.iter().map(|t| t.eco_score as f64).sum::<f64>() / recent.len() as f64;
        Some(round_half_up_i32(result.eco_score as f64 - avg))
    }

    /// 리포트에 쓰는 평소 대비 한 줄.
    pub fn delta_text(delta: i32) -> String {
        if delta >= 1 {
            format!("평소보다 에코 +{delta}점")
        } else if delta <= -1 {
            format!("평소보다 에코 {delta}점")
        } else {
            "에코 점수가 평소와 같아요".to_string()
        }
    }

    /// 주차했을 때 읽어 주는 한 줄 요약.
    pub fn parking_summary(result: &TripResult, delta: Option<i32>) -> String {
        let mut out = format!("주행 끝. 종합 {}점이에요.", result.score);
        if let Some(d) = delta {
            if d >= 1 {
                out.push_str(&format!(" 에코는 평소보다 {d}점 높아요."));
            } else if d <= -1 {
                out.push_str(&format!(" 에코는 평소보다 {}점 낮아요.", -d));
            } else {
                out.push_str(" 에코는 평소와 같아요.");
            }
        }
        if let Some(detail) = &result.eco_detail {
            if let Some(weakest) = detail.weakest() {
                out.push_str(&format!(" {} 아쉬웠어요.", Self::with_subject(weakest.label())));
            } else {
                out.push_str(" 운전 습관이 모두 좋았어요.");
            }
        }
        out
    }

    /// 받침이 있으면 "이", 없으면 "가"를 붙인다.
    pub fn with_subject(word: &str) -> String {
        let Some(last) = word.chars().last() else {
            return word.to_string();
        };
        if !('가'..='힣').contains(&last) {
            return format!("{word}이");
        }
        let has_final = ((last as u32) - ('가' as u32)) % 28 != 0;
        if has_final {
            format!("{word}이")
        } else {
            format!("{word}가")
        }
    }
}

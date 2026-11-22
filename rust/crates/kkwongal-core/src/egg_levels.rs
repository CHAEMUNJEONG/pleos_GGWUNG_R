use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EggLevel {
    pub name: String,
    pub min_eggs: i32,
}

/// 꿩알 성장 단계. 새로운 장소의 스탬프 하나가 알 하나이고, 알이 모이면 꿩이 자란다.
pub struct EggLevels;

impl EggLevels {
    pub const LEVELS: [(&'static str, i32); 4] = [
        ("알", 0),
        ("아기 꿩", 5),
        ("어른 꿩", 15),
        ("황금 꿩", 30),
    ];

    pub fn levels() -> Vec<EggLevel> {
        Self::LEVELS
            .iter()
            .map(|&(name, min_eggs)| EggLevel {
                name: name.to_string(),
                min_eggs,
            })
            .collect()
    }

    pub fn current(eggs: i32) -> EggLevel {
        let clamped = eggs.max(0);
        let &(name, min_eggs) = Self::LEVELS
            .iter()
            .rev()
            .find(|&&(_, min_e)| clamped >= min_e)
            .unwrap_or(&Self::LEVELS[0]);
        EggLevel {
            name: name.to_string(),
            min_eggs,
        }
    }

    /// 다음 단계. 마지막 단계면 None.
    pub fn next(eggs: i32) -> Option<EggLevel> {
        Self::LEVELS
            .iter()
            .find(|&&(_, min_e)| min_e > eggs)
            .map(|&(name, min_eggs)| EggLevel {
                name: name.to_string(),
                min_eggs,
            })
    }

    /// 다음 단계까지 남은 알 개수. 마지막 단계면 0.
    pub fn remaining(eggs: i32) -> i32 {
        Self::next(eggs).map(|lvl| lvl.min_eggs - eggs).unwrap_or(0)
    }

    /// 현재 단계에서 다음 단계까지 얼마나 왔는지 (0~1). 마지막 단계면 1.
    pub fn progress(eggs: i32) -> f32 {
        let now = Self::current(eggs);
        let Some(nxt) = Self::next(eggs) else {
            return 1.0;
        };
        (eggs - now.min_eggs) as f32 / (nxt.min_eggs - now.min_eggs) as f32
    }
}

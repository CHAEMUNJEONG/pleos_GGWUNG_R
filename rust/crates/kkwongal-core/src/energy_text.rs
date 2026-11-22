use crate::energy::EnergySnapshot;

/// 연결 점검 화면에 보여 줄 에너지 값.
pub fn energy_text(e: &EnergySnapshot) -> String {
    let mut parts = Vec::new();
    if let Some(wh) = e.ev_battery_wh {
        parts.push(format!("배터리 {:.1} kWh", wh / 1000.0));
    }
    if let Some(pct) = e.fuel_percent {
        parts.push(format!("연료 {:.0}%", pct));
    }
    if let Some(odo) = e.odometer_km {
        parts.push(format!("누적 {} km", format_grouped_zero_decimals(odo)));
    }
    if parts.is_empty() {
        "값 없음".to_string()
    } else {
        parts.join(" · ")
    }
}

pub fn format_grouped_zero_decimals(value: f64) -> String {
    let rounded = value.round();
    let negative = rounded < 0.0;
    let digits = format!("{:.0}", rounded.abs());
    let mut out = String::with_capacity(digits.len() + digits.len() / 3 + 1);
    if negative {
        out.push('-');
    }
    let len = digits.len();
    for (i, ch) in digits.chars().enumerate() {
        if i > 0 && (len - i) % 3 == 0 {
            out.push(',');
        }
        out.push(ch);
    }
    out
}

pub fn format_grouped_one_decimal(value: f64) -> String {
    let formatted = format!("{:.1}", value);
    let (sign, rest) = if let Some(stripped) = formatted.strip_prefix('-') {
        ("-", stripped)
    } else {
        ("", formatted.as_str())
    };
    let (int_part, frac_part) = rest.split_once('.').unwrap_or((rest, "0"));
    let mut out = String::with_capacity(rest.len() + int_part.len() / 3 + 2);
    out.push_str(sign);
    let len = int_part.len();
    for (i, ch) in int_part.chars().enumerate() {
        if i > 0 && (len - i) % 3 == 0 {
            out.push(',');
        }
        out.push(ch);
    }
    out.push('.');
    out.push_str(frac_part);
    out
}

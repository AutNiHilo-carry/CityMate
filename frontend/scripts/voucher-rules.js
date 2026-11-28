// Show verbatim excerpts, never turn free text into invented eligibility facts.
export function voucherRuleHighlights(value) {
  const text = String(value || '').replace(/\\n/g, '\n').trim();
  const clauses = text.split(/[。；;\n]+/).map(part => part.trim()).filter(Boolean);
  return [
    ['使用门槛', /满\s*\d|最低消费|无门槛|消费满/],
    ['适用时间', /周[一二三四五六日天末](?:至|到|—|[-~～]|使用|可用|不可|不适用|可|需|须|限|全天|通用)|工作日(?:使用|可|不|需|限)|节假日(?:使用|可|不|需|限|除)|午市|晚市|\d{1,2}[:：]\d{2}|有效期|使用日期/],
    ['预约要求', /预约/],
    ['叠加限制', /叠加|同享|同用|每单|限用/],
  ].map(([label, pattern]) => ({label, text: clauses.filter(clause => pattern.test(clause)).join('；') || '原文未说明，请确认',
    known: clauses.some(clause => pattern.test(clause))}));
}

export function voucherRuleSummary(value) {
  const fields = voucherRuleHighlights(value), excerpts = [];
  for (const field of fields.filter(field => field.known)) {
    const existing = excerpts.find(excerpt => excerpt.text === field.text);
    if (existing) existing.labels.push(field.label);
    else excerpts.push({labels: [field.label], text: field.text});
  }
  return {excerpts, missing: fields.filter(field => !field.known).map(field => field.label)};
}

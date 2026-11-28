import { imagePath, images, money, contextualHref } from "./api.js";
import { voucherRuleSummary } from "./voucher-rules.js";

Vue.component("photo-viewer", {
  props: ["pictures", "startIndex", "title"],
  data() {
    return {
      index: Math.max(0, Math.min(this.pictures.length - 1, Number(this.startIndex) || 0)),
      touchStart: null,
    };
  },
  mounted() {
    this.previousFocus = document.activeElement;
    this.previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    this.background = Array.from(this.$el.parentElement.children)
      .filter(node => node !== this.$el)
      .map(node => ({ node, inert: node.inert }));
    this.background.forEach(({ node }) => { node.inert = true; });
    this.keyboardListener = event => this.onKey(event);
    window.addEventListener("keydown", this.keyboardListener);
    this.$refs.close.focus();
  },
  beforeDestroy() {
    window.removeEventListener("keydown", this.keyboardListener);
    document.body.style.overflow = this.previousOverflow;
    this.background.forEach(({ node, inert }) => { node.inert = inert; });
    if (this.previousFocus && this.previousFocus.isConnected) this.previousFocus.focus();
  },
  methods: {
    move(step) {
      const next = Math.max(0, Math.min(this.pictures.length - 1, this.index + step));
      if (next !== this.index) { this.index = next; this.$emit("change", next); }
    },
    onKey(event) {
      if (event.key === "Escape") { event.preventDefault(); this.$emit("close"); }
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault(); this.move(event.key === "ArrowLeft" ? -1 : 1);
      }
      if (event.key === "Tab") {
        const buttons = Array.from(this.$el.querySelectorAll("button:not([disabled])"));
        const index = buttons.indexOf(document.activeElement);
        if (event.shiftKey && index <= 0) { event.preventDefault(); buttons[buttons.length - 1].focus(); }
        else if (!event.shiftKey && (index < 0 || index === buttons.length - 1)) { event.preventDefault(); buttons[0].focus(); }
      }
    },
    startTouch(event) {
      this.touchStart = event.touches.length === 1
        ? { x: event.touches[0].clientX, y: event.touches[0].clientY } : null;
    },
    endTouch(event) {
      const start = this.touchStart;
      this.touchStart = null;
      if (!start || event.touches.length || event.changedTouches.length !== 1) return;
      const dx = event.changedTouches[0].clientX - start.x;
      const dy = event.changedTouches[0].clientY - start.y;
      if (Math.abs(dx) >= 60 && Math.abs(dx) > Math.abs(dy) * 1.5) this.move(dx < 0 ? 1 : -1);
    },
  },
  template: `
    <section class="photo-viewer" role="dialog" aria-modal="true" aria-label="照片查看器" @click.self="$emit('close')">
      <header class="photo-viewer-toolbar"><div><strong>{{ title || '照片' }}</strong><span aria-live="polite">第 {{ index + 1 }} 张 / 共 {{ pictures.length }} 张</span></div><button ref="close" type="button" aria-label="关闭大图" @click="$emit('close')"><i class="el-icon-close" aria-hidden="true"></i></button></header>
      <div class="photo-viewer-stage" @click.self="$emit('close')" @touchstart="startTouch" @touchend="endTouch" @touchcancel="touchStart = null" @touchmove="($event.touches.length > 1) && (touchStart = null)">
        <app-image :src="pictures[index]" :alt="(title || '照片') + '，第 ' + (index + 1) + ' 张'" eager="true"></app-image>
        <button v-if="pictures.length > 1" type="button" class="photo-viewer-prev" aria-label="大图上一张" :disabled="index === 0" @click="move(-1)"><i class="el-icon-arrow-left" aria-hidden="true"></i></button>
        <button v-if="pictures.length > 1" type="button" class="photo-viewer-next" aria-label="大图下一张" :disabled="index === pictures.length - 1" @click="move(1)"><i class="el-icon-arrow-right" aria-hidden="true"></i></button>
      </div>
      <p class="photo-viewer-help">滑动或点击箭头切换 · 点击 × 关闭</p>
    </section>`,
});

Vue.component("app-image", {
  props: ["src", "alt", "eager"],
  data() {
    return { failed: false };
  },
  watch: {
    src() {
      this.failed = false;
    },
  },
  computed: {
    source() {
      return this.failed
        ? "/assets/image-placeholder.svg"
        : imagePath(this.src);
    },
  },
  template:
    '<img :src="source" :alt="alt || \'\'" :loading="eager ? \'eager\' : \'lazy\'" decoding="async" referrerpolicy="no-referrer" @error="failed = true">',
});

Vue.component("blog-card", {
  props: ["blog"],
  methods: { images, contextLink(target) { return contextualHref(target, location.pathname + location.search + (location.hash || ''), location.origin); } },
  template: `
    <article class="blog-card">
      <a :href="contextLink('/blog-detail.html?id=' + blog.id)" class="blog-cover" :aria-label="blog.title">
        <app-image :src="images(blog.images)[0]" :alt="blog.title"></app-image>
        <span v-if="images(blog.images).length > 1" class="blog-picture-count"><i class="el-icon-picture-outline" aria-hidden="true"></i>{{ images(blog.images).length }}</span>
      </a>
      <div class="blog-card-body">
        <a :href="contextLink('/blog-detail.html?id=' + blog.id)" class="blog-card-title" :title="blog.title">{{ blog.title }}</a>
        <div class="blog-card-footer">
          <a :href="'/other-info.html?id=' + blog.userId" class="author">
            <app-image :src="blog.icon || '/imgs/icons/default-icon.png'" alt=""></app-image>
            <span>{{ blog.name || '用户' }}</span>
          </a>
          <button class="like-button" :class="{ liked: blog.isLike }" :disabled="blog.busy" :aria-pressed="!!blog.isLike" :aria-label="(blog.isLike ? '取消点赞：' : '点赞：') + blog.title" @click="$emit('like', blog)">
            <svg class="like-heart" viewBox="0 0 24 24" aria-hidden="true"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"></path></svg><span>{{ blog.liked || '赞' }}</span>
          </button>
        </div>
      </div>
    </article>`,
});

Vue.component("rating-stars", {
  props: ["score"],
  computed: {
    fill() {
      return Math.max(0, Math.min(100, Number(this.score || 0) * 2)) + "%";
    },
  },
  template:
    '<span class="rating-stars" aria-hidden="true">★★★★★<span :style="{ width: fill }">★★★★★</span></span>',
});

Vue.component("shop-card", {
  props: ["shop", "typeName"],
  methods: { images, contextLink(target) { return contextualHref(target, location.pathname + location.search + (location.hash || ''), location.origin); } },
  template: `
    <a class="shop-card" :href="contextLink('/shop-detail.html?id=' + shop.id)">
      <div class="shop-cover"><app-image :src="images(shop.images)[0]" :alt="shop.name"></app-image><span v-if="typeName" class="image-tag">{{ typeName }}</span></div>
      <div class="shop-card-body"><h3>{{ shop.name }}</h3>
        <div class="shop-score"><rating-stars :score="shop.score"></rating-stars><strong>{{ shop.score == null ? '暂无评分' : (shop.score / 10).toFixed(1) }}</strong><span class="muted">{{ shop.comments || 0 }} 条评价</span></div>
        <p class="muted shop-address"><i class="el-icon-location-outline" aria-hidden="true"></i>{{ shop.area || shop.address }}</p>
        <div class="shop-card-bottom"><span><strong>¥{{ shop.avgPrice == null ? '—' : shop.avgPrice }}</strong> / 人</span><span class="muted">查看店铺 <i class="el-icon-right" aria-hidden="true"></i></span></div>
      </div>
    </a>`,
});

Vue.component("voucher-card", {
  props: ["voucher", "shopName", "busy"],
  methods: {
    money,
    contextLink(target) { return contextualHref(target, location.pathname + location.search + (location.hash || ''), location.origin); },
    rulesText(value) { return String(value || "使用前请向店铺确认适用范围").replace(/\\n/g, "\n"); },
  },
  computed: {
    savingCents() {
      const pay = this.voucher.payValue, value = this.voucher.actualValue;
      if (pay == null || value == null || pay === "" || value === "") return 0;
      const paid = Number(pay), face = Number(value);
      return Number.isSafeInteger(paid) && Number.isSafeInteger(face) && paid > 0 && face > paid ? face - paid : 0;
    },
    ruleSummary() { return voucherRuleSummary(this.voucher.rules); },
    available() {
      const v = this.voucher;
      if (v.type == null || ![0, 1].includes(Number(v.type))
          || (v.status != null && Number(v.status) !== 1)) {
        return false;
      }
      // Server clock determines activity windows. The confirmation path refreshes this snapshot.
      return v.orderable === true;
    },
    label() {
      if (this.busy) {
        return "正在提交…";
      }
      if (!this.available) {
        return "暂不可领取";
      }
      return Number(this.voucher.type) === 1
        ? "限时抢购"
        : Number(this.voucher.payValue) === 0
          ? "立即领取"
          : "购买优惠券";
    },
  },
  template: `
    <article class="voucher-card" :class="{ 'voucher-unavailable': !available }">
      <div class="voucher-face"><div><span>¥</span><strong>{{ money(voucher.payValue) }}</strong></div><span>{{ Number(voucher.payValue) === 0 ? '免费领取' : '优惠券售价' }}</span></div>
      <div class="voucher-main"><a v-if="shopName" :href="contextLink('/shop-detail.html?id=' + voucher.shopId)" class="eyebrow">{{ shopName }}</a><h3>{{ voucher.title }}</h3><p>{{ voucher.subTitle || '使用规则详见店铺说明' }}</p><div class="voucher-meta"><span>券面 ¥{{ money(voucher.actualValue) }}</span><span v-if="savingCents" class="voucher-saving">比券面少付 ¥{{ money(savingCents) }}</span><span v-if="Number(voucher.type) === 1" class="tag">限时券<span v-if="voucher.stock != null"> · 剩余 {{ voucher.stock }} 张</span></span></div><details class="voucher-rules"><summary>使用规则<i class="el-icon-arrow-down" aria-hidden="true"></i></summary><p>{{ rulesText(voucher.rules) }}</p></details></div>
      <section class="voucher-key-rules" aria-label="用券限制摘录"><h4><i class="el-icon-info" aria-hidden="true"></i>用券前确认 <span>规则原文摘录</span></h4><ul v-if="ruleSummary.excerpts.length"><li v-for="excerpt in ruleSummary.excerpts" :key="excerpt.text"><strong>{{ excerpt.labels.join(' / ') }}</strong><span>{{ excerpt.text }}</span></li></ul><p v-if="ruleSummary.missing.length" class="voucher-rule-unknown">待确认：{{ ruleSummary.missing.join('、') }}。原文未明确说明。</p></section>
      <button class="button primary" :disabled="!available || busy" @click="$emit('claim', voucher)">{{ label }}</button>
    </article>`,
});

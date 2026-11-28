import { createApi, images, imagePath, money, safeReturn, contextualHref, returnTarget } from "./api.js";
import { claimPreview, claimFingerprint } from "./assistant-helpers.js";
import { noteDraftKey, readNoteDraft, encodeNoteDraft, hasNoteContent } from "./note-draft.js";
import "./components.js";
import "./experience.js";
import "./assistant.js";
import "./favorites.js";

const query = new URLSearchParams(location.search);
const routes = {
  "/": "home",
  "/index.html": "home",
  "/shop-list.html": "shops",
  "/shop-detail.html": "shop",
  "/blog-detail.html": "blog",
  "/blog-edit.html": "publish",
  "/login.html": "login",
  "/login2.html": "login",
  "/info.html": "profile",
  "/other-info.html": "profile",
  "/orders.html": "orders",
  "/vouchers.html": "vouchers",
  "/experience.html": "experience",
  "/assistant.html": "assistant",
};
const page = routes[location.pathname] || "not-found";
let app;
const apiOptions = {
  tokenProvider: () => sessionStorage.getItem("token"),
  onUnauthorized: (quiet) => {
    sessionStorage.removeItem("token");
    if (app) {
      app.user = null;
    }
    if (!quiet) {
      location.assign(
        "/login.html?return=" +
          encodeURIComponent(location.pathname + location.search + (location.hash || '')),
      );
    }
  },
};
const api = createApi(apiOptions);
const assistantApi = createApi({ ...apiOptions, timeout: 30000 });

app = new Vue({
  el: "#app",
  data: {
    page,
    user: null,
    userReady: false,
    types: [],
    loading: true,
    pageError: "",
    keyword: query.get("keyword") || "",
    toast: null,
    dialog: null,
    blogs: [],
    blogPage: 1,
    blogLoading: false,
    blogSequence: 0,
    blogHasMore: true,
    feed: query.get("feed") === "follow" ? "follow" : "hot",
    feedLastId: Date.now(),
    feedOffset: 0,
    homeShops: [],
    homeOffer: null,
    asideError: "",
    shops: [],
    catalogPage: 1,
    catalogTotal: 0,
    catalogLoading: false,
    catalogSequence: 0,
    catalogType: query.get("type") || "",
    catalogSort: query.get("sort") || "score",
    catalogKeyword: query.get("keyword") || "",
    catalogArea: query.get("area") || "",
    catalogMaxPrice: query.get("maxPrice") || "",
    catalogMinScore: query.get("minScore") || "",
    catalogHasVoucher: query.get("hasVoucher") === "true",
    catalogCriteria: {},
    shop: null,
    vouchers: [],
    shopBlogs: [],
    shopBlogsPage: 1,
    shopBlogsTotal: 0,
    shopBlogsLoading: false,
    shopBlogsError: "",
    shopVouchersLoading: false,
    shopVouchersError: "",
    blog: null,
    blogShop: null,
    following: false,
    followingBusy: false,
    galleryIndex: 0,
    photoViewer: null,
    claimBusy: "",
    cancelBusy: false,
    orderError: "",
    offerPage: 1,
    offerTotal: 0,
    offerKeyword: query.get("keyword") || "",
    offerType: query.get("type") || "",
    offerShopId: query.get("shopId") || "",
    offerShopOptions: [],
    offerShopOptionsMore: false,
    offerSequence: 0,
    offerFacetSequence: 0,
    offerCriteria: {},
    offerError: "",
    offerShopError: "",
    offerShopLoading: false,
    offers: [],
    offerLoading: false,
    offerHasMore: true,
    profileUser: null,
    signCount: 0,
    signCountError: "",
    signCountLoading: false,
    signCountSequence: 0,
    signBusy: false,
    signed: false,
    orders: [],
    orderPage: 1,
    orderTotal: 0,
    orderLoading: false,
    orderSequence: 0,
    orderScope: query.get("tab") === "held" ? "held" : "all",
    copyingOrderId: "",
    pendingRequest: null,
    requestMessage: "",
    phone: "",
    code: "",
    loginBusy: false,
    codeBusy: false,
    codeCountdown: 0,
    loginError: "",
    publishShops: [],
    shopQuery: "",
    publishShopLoading: false,
    publishShopSequence: 0,
    publishShopError: "",
    uploadBusy: false,
    publishBusy: false,
    draft: {
      title: "",
      content: "",
      shopId: query.get("shop") || "",
      pictures: [],
    },
    draftSaved: false,
    draftStorageReady: false,
    draftRecovery: null,
    draftSaveState: "idle",
    draftDiscardConfirm: false,
    publishUncertain: false,
  },
  computed: {
    loginHref() {
      return '/login.html?return=' + encodeURIComponent(safeReturn(location.pathname + location.search + (location.hash || ''), location.origin));
    },
    detailBackHref() {
      const fallback = this.page === 'shop' ? '/shop-list.html' + (this.shop ? '?type=' + this.shop.typeId : '') : '/';
      return returnTarget(query.get('from'), fallback, location.origin);
    },
    detailBackLabel() {
      const path = this.detailBackHref.split('?')[0].split('#')[0];
      return ({'/shop-list.html':'返回搜索结果','/vouchers.html':'返回优惠券列表',
        '/blog-detail.html':'返回探店笔记','/shop-detail.html':'返回店铺','/assistant.html':'返回小橙',
        '/':'返回发现页','/index.html':'返回发现页'})[path] || '返回上一页';
    },
    headerSearchLabel() {
      return this.page === "vouchers" ? "搜索优惠券或店铺" : "搜索商家";
    },
    headerSearchPlaceholder() {
      return this.page === "vouchers" ? "搜索优惠券、店铺名称" : "搜索店铺名称";
    },
    showMobileNav() {
      return !["login", "publish", "experience", "not-found"].includes(this.page);
    },
    activeNav() {
      if (this.page === "assistant") return "assistant";
      if (this.page === "experience") return "experience";
      if (this.page === "vouchers") {
        return "vouchers";
      }
      if (["shops", "shop"].includes(this.page)) {
        return "shops";
      }
      return ["home", "blog"].includes(this.page) ? "home" : "";
    },
    currentType() {
      return this.types.find(
        (type) => String(type.id) === String(this.catalogType),
      );
    },
    isOwnProfile() {
      return (
        !query.get("id") ||
        (this.user && String(query.get("id")) === String(this.user.id))
      );
    },
    shopPictures() {
      return images(this.shop && this.shop.images);
    },
    blogPictures() {
      return images(this.blog && this.blog.images);
    },
    catalogHasMore() {
      return (this.catalogPage - 1) * 12 < this.catalogTotal;
    },
    offerSelectedShopMissing() {
      return !!this.offerShopId && !this.offerShopOptions.some(shop => String(shop.id) === String(this.offerShopId));
    },
    profileName() {
      return this.profileUser ? this.profileUser.nickName : "探店记录员";
    },
    publishShopName() {
      const shop = this.publishShops.find(row => String(row.id) === this.draft.shopId);
      return shop ? shop.name : "尚未选择店铺";
    },
  },
  created() {
    this.toastTimer = null;
    this.codeTimer = null;
    this.requestTimer = null;
    this.draftTimer = null;
    this.boot();
  },
  beforeDestroy() {
    clearTimeout(this.toastTimer);
    clearInterval(this.codeTimer);
    clearTimeout(this.requestTimer);
    clearTimeout(this.draftTimer);
    if (this.page === "publish") this.persistDraft();
    this.releaseDialog(false);
  },
  watch: {
    draft: {
      deep: true,
      handler() {
        if (this.page !== "publish" || !this.draftStorageReady || this.draftSaved) return;
        this.draftSaveState = "pending";
        clearTimeout(this.draftTimer);
        this.draftTimer = setTimeout(() => this.persistDraft(), 500);
      },
    },
    dialog(value) {
      this.$nextTick(() => {
        if (this.dialog !== value) return;
        if (value) {
          if (!this.dialogBackground) {
            this.dialogOrigin = document.activeElement;
            this.dialogOverflow = document.body.style.overflow;
            document.body.style.overflow = "hidden";
            const overlay = this.$refs.dialogPanel && this.$refs.dialogPanel.closest(".dialog-overlay");
            this.dialogBackground = overlay ? Array.from(overlay.parentElement.children)
              .filter(node => node !== overlay).map(node => ({ node, inert: node.inert })) : [];
            this.dialogBackground.forEach(({ node }) => { node.inert = true; });
          }
          const button =
            this.$refs.dialogPanel &&
            this.$refs.dialogPanel.querySelector("button");
          if (button) {
            button.focus();
          }
        } else {
          this.releaseDialog();
        }
      });
    },
  },
  methods: {
    releaseDialog(restoreFocus = true) {
      if (!this.dialogBackground) return;
      document.body.style.overflow = this.dialogOverflow;
      this.dialogBackground.forEach(({ node, inert }) => { node.inert = inert; });
      this.dialogBackground = null;
      if (restoreFocus && this.dialogOrigin && this.dialogOrigin.isConnected) this.dialogOrigin.focus();
    },
    openPhotos(pictures, index, title) {
      if (!Array.isArray(pictures) || !pictures.length) return;
      this.photoViewer = { pictures: pictures.slice(), index, title };
    },
    assistantApi(path, options) {
      return assistantApi(path, options);
    },
    favoriteApi(path, options) {return api(path, options);},
    experienceApi(path, options) {
      return api(path, options);
    },
    images,
    imagePath,
    money,
    contextLink(target) { return contextualHref(target, location.pathname + location.search + (location.hash || ''), location.origin); },
    typeName(id) {
      const type = this.types.find((item) => String(item.id) === String(id));
      return type ? type.name : "本地好店";
    },
    typeIcon(id) {
      return (
        {
          1: "knife-fork",
          2: "microphone",
          3: "female",
          10: "magic-stick",
          5: "coffee-cup",
          6: "cherry",
          7: "user",
          8: "goblet",
          9: "house",
          4: "basketball",
        }[id] || "discover"
      );
    },
    date(value) {
      if (!value) {
        return "—";
      }
      const time = new Date(value);
      return Number.isNaN(time.getTime())
        ? "—"
        : time.toLocaleDateString("zh-CN");
    },
    rulesText(value) { return String(value || "使用前请向店铺确认适用范围").replace(/\\n/g, "\n"); },
    async copyOrderId(id) {
      const text = String(id || "");
      if (!/^[1-9]\d*$/.test(text) || this.copyingOrderId) return;
      this.copyingOrderId = text;
      try {
        if (!navigator.clipboard || !navigator.clipboard.writeText) throw new Error("Clipboard unavailable");
        await navigator.clipboard.writeText(text);
        this.notify("订单号已复制");
      } catch { this.notify("复制失败，可选中订单编号手动复制", "error"); }
      finally { this.copyingOrderId = ""; }
    },
    orderStatus(status) {
      return (
        {
          1: "待付款",
          2: "已领取",
          3: "已使用",
          4: "已取消",
          5: "退款中",
          6: "已退款",
        }[status] || "处理中"
      );
    },
    notify(message, kind = "success") {
      this.toast = { message, kind };
      clearTimeout(this.toastTimer);
      this.toastTimer = setTimeout(() => {
        this.toast = null;
      }, 4500);
    },
    showError(error) {
      if (error.status !== 401) {
        this.notify(error.message || "操作失败，请重试", "error");
      }
    },
    reload() {
      location.reload();
    },
    trapDialog(event) {
      const controls = this.$refs.dialogPanel.querySelectorAll(
        "button:not([disabled]), a[href], input, select, textarea",
      );
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      }
      if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    },
    requireUser() {
      if (this.user) {
        return true;
      }
      location.assign(this.loginHref);
      return false;
    },
    async boot() {
      if (sessionStorage.getItem("token")) {
        try {
          this.user = (await api("/user/me", { quiet: true })).data;
        } catch (error) {
          if (error.status !== 401) {
            this.showError(error);
          }
        }
      }
      this.userReady = true;
      if (this.page === "assistant") {
        this.loading = false;
        document.title = "找店查券，问一问 · CityMate";
        return;
      }
      if (["orders", "publish", "blog", "profile"].includes(this.page)) {
        if (!this.requireUser()) {
          return;
        }
      }
      try {
        this.types = (await api("/shop-type/list")).data || [];
      } catch (error) {
        this.showError(error);
      }
      try {
        if (this.page === "home") {
          await this.loadHome();
        }
        if (this.page === "shops") {
          await this.loadCatalog(true);
        }
        if (this.page === "shop") {
          await this.loadShop();
        }
        if (this.page === "blog") {
          await this.loadBlog();
        }
        if (this.page === "vouchers") {
          await this.loadOffers(true);
        }
        if (this.page === "profile") {
          await this.loadProfile();
        }
        if (this.page === "orders") {
          await this.loadOrders();
          this.watchRequest();
        }
        if (this.page === "publish") {
          this.loadLocalDraft();
          await this.loadPublishShops();
        }
      } catch (error) {
        if (error.status !== 401) {
          this.pageError = error.message;
        }
      } finally {
        this.loading = false;
      }
      document.title =
        (this.shop
          ? this.shop.name
          : this.blog
            ? this.blog.title
            : {
                shops: "发现好店",
                vouchers: "超值优惠",
                profile: this.profileName,
                orders: "我的订单",
                publish: "发布探店笔记",
                login: "欢迎回来",
                experience: "体验验证",
              }[this.page] || "发现身边的好去处") + " · CityMate";
    },
    search() {
      const keyword = this.keyword.trim();
      if (this.page === "vouchers") {
        this.offerKeyword = keyword;
        return this.loadOffers(true);
      }
      if (this.page === "shops") {
        this.catalogKeyword = keyword;
        return this.loadCatalog(true);
      }
      location.assign(
        "/shop-list.html?" +
          new URLSearchParams({ keyword }),
      );
    },
    async loadHome() {
      const results = await Promise.allSettled([
        this.loadBlogs(true),
        api("/shop/catalog", { params: { page: 1, size: 3, sort: "score" } }),
      ]);
      if (results[1].status === "fulfilled") {
        this.homeShops = results[1].value.data || [];
      } else {
        this.asideError = results[1].reason.message;
      }
      const ids = [
        ...new Set(
          this.blogs
            .map((blog) => blog.shopId)
            .concat(this.homeShops.map((shop) => shop.id)),
        ),
      ];
      if (!ids.length) {
        return;
      }
      const vouchers = await Promise.allSettled(
        ids.map((id) => api("/voucher/list/" + id)),
      );
      const offers = vouchers.flatMap((item) =>
        item.status === "fulfilled" ? item.value.data || [] : [],
      );
      this.homeOffer =
        offers.find((offer) => offer.orderable === true && offer.type === 0) ||
        null;
    },
    async changeFeed(feed) {
      if (!["hot", "follow"].includes(feed) || feed === this.feed) {
        return;
      }
      if (feed === "follow" && !this.requireUser()) {
        return;
      }
      this.feed = feed;
      this.pageError = "";
      await this.loadBlogs(true);
    },
    async loadBlogs(reset = false) {
      if (this.blogLoading && !reset) {
        return;
      }
      const sequence = ++this.blogSequence;
      const followFeed = this.feed === "follow" && this.page !== "profile";
      this.blogLoading = true;
      this.pageError = "";
      if (reset) {
        this.blogs = [];
        this.blogPage = 1;
        this.feedLastId = Date.now();
        this.feedOffset = 0;
        this.blogHasMore = true;
      }
      try {
        let endpoint = "/blog/hot";
        let params = { current: this.blogPage };
        if (this.page === "profile") {
          endpoint = this.isOwnProfile ? "/blog/of/me" : "/blog/of/user";
          params.id = query.get("id");
        } else if (followFeed) {
          if (!this.requireUser()) {
            return;
          }
          endpoint = "/blog/of/follow";
          params = { lastId: this.feedLastId, offset: this.feedOffset };
        }
        const result = await api(endpoint, { params });
        if (sequence !== this.blogSequence) return;
        const rows =
          followFeed
            ? (result.data && result.data.list) || []
            : result.data || [];
        if (this.page === "profile" && this.profileUser) {
          rows.forEach((blog) => {
            blog.name = blog.name || this.profileUser.nickName;
            blog.icon = blog.icon || this.profileUser.icon;
          });
        }
        const seen = new Set(this.blogs.map(blog => String(blog.id)));
        for (const blog of rows) {
          if (!seen.has(String(blog.id))) { this.blogs.push(blog); seen.add(String(blog.id)); }
        }
        this.blogHasMore = rows.length > 0;
        this.blogPage += 1;
        if (result.data && result.data.minTime != null) {
          this.feedLastId = result.data.minTime;
          this.feedOffset = result.data.offset;
        }
      } catch (error) {
        if (sequence === this.blogSequence) this.pageError = error.message;
      } finally {
        if (sequence === this.blogSequence) this.blogLoading = false;
      }
    },
    async like(blog) {
      if (!this.requireUser() || blog.busy) {
        return;
      }
      this.$set(blog, "busy", true);
      try {
        await api("/blog/like/" + blog.id + "/state", {
          method: "PUT",
          params: { liked: !blog.isLike },
        });
        const fresh = (await api("/blog/" + blog.id)).data;
        this.$set(blog, "liked", fresh.liked);
        this.$set(blog, "isLike", fresh.isLike);
      } catch (error) {
        this.showError(error);
      } finally {
        this.$set(blog, "busy", false);
      }
    },
    async loadCatalog(reset = false) {
      if (this.catalogLoading && !reset) {
        return;
      }
      const sequence = ++this.catalogSequence;
      if (reset) {
        this.catalogPage = 1;
        this.shops = [];
        this.catalogTotal = 0;
        this.catalogCriteria = {
          keyword: this.catalogKeyword.trim(), typeId: this.catalogType, sort: this.catalogSort,
          area: this.catalogArea.trim(), maxPrice: this.catalogMaxPrice,
          minScore: this.catalogMinScore, hasVoucher: this.catalogHasVoucher ? true : undefined,
        };
        this.keyword = this.catalogCriteria.keyword;
        this.syncCatalogUrl();
      }
      this.catalogLoading = true;
      this.pageError = "";
      try {
        const result = await api("/shop/catalog", {
          params: {
            ...this.catalogCriteria,
            page: this.catalogPage,
            size: 12,
          },
        });
        if (sequence !== this.catalogSequence) {
          return;
        }
        const seen = new Set(this.shops.map(row => String(row.id)));
        for (const row of result.data || []) {
          if (!seen.has(String(row.id))) { this.shops.push(row); seen.add(String(row.id)); }
        }
        this.catalogTotal = Number(result.total || 0);
        this.catalogPage += 1;
      } catch (error) {
        if (sequence === this.catalogSequence) {
          this.pageError = error.message;
        }
      } finally {
        if (sequence === this.catalogSequence) {
          this.catalogLoading = false;
        }
      }
    },
    resetCatalogFilters() {
      this.catalogKeyword = this.catalogArea = this.catalogMaxPrice = this.catalogMinScore = "";
      this.catalogType = "";
      this.catalogSort = "score";
      this.catalogHasVoucher = false;
      return this.loadCatalog(true);
    },
    syncCatalogUrl() {
      if (this.page !== "shops" || typeof history === "undefined") return;
      const url = new URL(location.href);
      const values = { keyword: this.catalogCriteria.keyword, type: this.catalogType,
        sort: this.catalogSort, area: this.catalogCriteria.area, maxPrice: this.catalogMaxPrice,
        minScore: this.catalogMinScore, hasVoucher: this.catalogHasVoucher ? "true" : "" };
      for (const [key,value] of Object.entries(values)) {
        if (value === "" || value == null || (key === "sort" && value === "score")) url.searchParams.delete(key);
        else url.searchParams.set(key,String(value));
      }
      history.replaceState(null,"",url.pathname+url.search);
    },
    async loadShop() {
      const id = query.get("id");
      if (!/^\d+$/.test(id || "")) {
        throw new Error("店铺地址无效，请从店铺列表重新进入");
      }
      this.shop = (await api("/shop/" + id)).data;
      if (!this.shop) {
        throw new Error("没有找到这家店铺");
      }
      await Promise.all([this.loadShopVouchers(), this.loadShopBlogs(true)]);
    },
    async loadShopVouchers() {
      if (!this.shop || this.shopVouchersLoading) return;
      this.shopVouchersLoading = true;
      this.shopVouchersError = "";
      try {
        this.vouchers = (await api("/voucher/list/" + this.shop.id)).data || [];
      } catch (error) {
        this.shopVouchersError = error.message;
      } finally { this.shopVouchersLoading = false; }
    },
    async loadShopBlogs(reset = false) {
      if (!this.shop || this.shopBlogsLoading) return;
      if (reset === true) { this.shopBlogsPage = 1; this.shopBlogs = []; this.shopBlogsTotal = 0; }
      this.shopBlogsLoading = true;
      this.shopBlogsError = "";
      try {
        const result = await api("/blog/of/shop", {
          params: { shopId: this.shop.id, current: this.shopBlogsPage },
        });
        const seen = new Set(this.shopBlogs.map(row => String(row.id)));
        for (const row of result.data || []) {
          if (!seen.has(String(row.id))) { this.shopBlogs.push(row); seen.add(String(row.id)); }
        }
        this.shopBlogsTotal = Number(result.total || 0);
        this.shopBlogsPage += 1;
      } catch (error) { this.shopBlogsError = error.message; }
      finally { this.shopBlogsLoading = false; }
    },
    async loadBlog() {
      const id = query.get("id");
      if (!/^\d+$/.test(id || "")) {
        throw new Error("笔记地址无效，请从首页重新进入");
      }
      this.blog = (await api("/blog/" + id)).data;
      if (!this.blog) {
        throw new Error("没有找到这篇笔记");
      }
      try {
        this.blogShop = (await api("/shop/" + this.blog.shopId)).data;
      } catch {
        /* The note remains readable if its shop is unavailable. */
      }
      if (this.user && String(this.user.id) !== String(this.blog.userId)) {
        this.following = Boolean(
          (await api("/follow/or/not/" + this.blog.userId)).data,
        );
      }
    },
    async toggleFollow() {
      if (!this.requireUser() || this.followingBusy) {
        return;
      }
      this.followingBusy = true;
      try {
        await api("/follow/" + this.blog.userId + "/" + !this.following, {
          method: "PUT",
        });
        this.following = !this.following;
        this.notify(this.following ? "已关注这位作者" : "已取消关注");
      } catch (error) {
        this.showError(error);
      } finally {
        this.followingBusy = false;
      }
    },
    async loadOffers(initial = false) {
      const reset = initial === true;
      if (this.offerLoading && !reset) {
        return;
      }
      const sequence = ++this.offerSequence;
      if (reset) {
        this.offerPage = 1;
        this.offers = [];
        this.offerTotal = 0;
        this.offerHasMore = false;
        this.offerShopOptionsMore = false;
        this.offerShopError = "";
        this.offerCriteria = {
          keyword: this.offerKeyword.trim(),
          typeId: this.offerType,
          shopId: this.offerShopId,
        };
        this.keyword = this.offerCriteria.keyword;
        this.syncOfferUrl();
      }
      this.offerError = "";
      this.offerLoading = true;
      const params = {
        ...this.offerCriteria,
        page: this.offerPage,
        size: 12,
      };
      if (reset) {
        this.loadOfferShops();
      }
      try {
        const result = await api("/voucher/catalog", { params });
        if (sequence !== this.offerSequence) return;
        this.offerTotal = Number(result.total || 0);
        const existing = new Set(this.offers.map((row) => String(row.id)));
        for (const row of result.data || []) {
          if (!existing.has(String(row.id))) {
            this.offers.push(row);
            existing.add(String(row.id));
          }
        }
        this.offerHasMore = this.offerPage * 12 < this.offerTotal;
        this.offerPage += 1;
      } catch (error) {
        if (sequence !== this.offerSequence) return;
        this.offerError = error.message || "优惠券加载失败，请重试";
      } finally {
        if (sequence === this.offerSequence) this.offerLoading = false;
      }
    },
    syncOfferUrl() {
      if (typeof history === "undefined") return;
      const url = new URL(location.href);
      const values = { keyword: this.offerCriteria.keyword, type: this.offerCriteria.typeId, shopId: this.offerCriteria.shopId };
      for (const [name, value] of Object.entries(values)) {
        if (value == null || value === "") url.searchParams.delete(name);
        else url.searchParams.set(name, String(value));
      }
      history.replaceState(null, "", url.pathname + url.search + url.hash);
    },
    async loadOfferShops() {
      const sequence = ++this.offerFacetSequence;
      const typeId = this.offerCriteria.typeId || "";
      this.offerShopLoading = true;
      this.offerShopError = "";
      try {
        const result = await api("/voucher/shops", { params: { typeId } });
        if (sequence !== this.offerFacetSequence) return;
        this.offerShopOptions = result.data.shops || [];
        this.offerShopOptionsMore = !!result.data.hasMore;
      } catch {
        if (sequence === this.offerFacetSequence) this.offerShopError = "店铺选项暂时没加载出来，优惠列表仍可正常查看。";
      } finally {
        if (sequence === this.offerFacetSequence) this.offerShopLoading = false;
      }
    },
    changeOfferType(id) {
      this.offerType = String(id || "");
      this.offerShopId = "";
      this.offerShopOptions = [];
      return this.loadOffers(true);
    },
    resetOfferFilters() {
      this.offerKeyword = "";
      this.offerType = "";
      this.offerShopId = "";
      return this.loadOffers(true);
    },
    async claim(voucher) {
      if (!this.requireUser() || this.claimBusy) {
        return;
      }
      const identity = String(this.user.id);
      this.claimBusy = String(voucher.id);
      try {
        const result = await api("/voucher/preview", {
          params: { shopId: voucher.shopId, voucherId: voucher.id },
        });
        const preview = claimPreview(result.data, {
          shopId: String(voucher.shopId), voucherId: String(voucher.id),
        });
        if (!this.user || String(this.user.id) !== identity) return;
        if (!preview.availableAtQuery) {
          this.notify("这张券暂时不能领取，请刷新优惠后再试", "error");
          return;
        }
        this.dialog = { kind: "claim", identity, preview, voucher: {
          ...voucher, title: preview.title, rules: preview.rules,
          type: preview.type, payValue: preview.payCents, actualValue: preview.valueCents,
        } };
      } catch (error) {
        this.showError(error);
      } finally {
        this.claimBusy = "";
      }
    },
    async confirmClaim() {
      if (this.claimBusy || !this.dialog || this.dialog.kind !== "claim") return;
      const dialog = this.dialog;
      const voucher = dialog.voucher;
      if (!this.user || String(this.user.id) !== dialog.identity) {
        this.dialog = null;
        this.notify("登录状态已变化，请重新选择优惠券", "error");
        return;
      }
      this.claimBusy = String(voucher.id);
      let submitted = false;
      try {
        const previewResult = await api("/voucher/preview", {
          params: { shopId: voucher.shopId, voucherId: voucher.id },
        });
        if (this.dialog !== dialog || !this.user || String(this.user.id) !== dialog.identity) return;
        const fresh = claimPreview(previewResult.data, {
          shopId: String(voucher.shopId), voucherId: String(voucher.id),
        });
        if (!fresh.availableAtQuery) {
          this.dialog = null;
          this.notify("这张券暂时不能领取，请刷新优惠后再试", "error");
          return;
        }
        if (claimFingerprint(fresh) !== claimFingerprint(dialog.preview)) {
          dialog.preview = fresh;
          dialog.voucher = { ...voucher, title: fresh.title, rules: fresh.rules,
            type: fresh.type, payValue: fresh.payCents, actualValue: fresh.valueCents };
          this.notify("优惠价格或规则已更新，请查看后再次确认", "error");
          return;
        }
        this.dialog = null;
        submitted = true;
        const result = await api(
          "/voucher-order/" +
            (voucher.type ? "seckill/" : "ordinary/") +
            voucher.id,
          { method: "POST" },
        );
        location.assign(
          "/orders.html" +
            (voucher.type
              ? "?request=" + encodeURIComponent(String(result.data))
              : ""),
        );
      } catch (error) {
        if (!submitted) {
          this.showError(error);
          return;
        }
        if (!error.status || error.status >= 500) {
          try {
            const existing = (
              await api("/voucher-order/of/voucher/" + voucher.id)
            ).data;
            if (existing) {
              location.assign("/orders.html");
              return;
            }
          } catch {
            /* Leave the uncertain result visible; do not automatically resubmit. */
          }
          this.notify("提交结果暂未确认，请到我的订单查看后再操作", "error");
        } else {
          this.showError(error);
        }
      } finally {
        this.claimBusy = "";
      }
    },
    async loadProfile() {
      this.profileUser = this.isOwnProfile
        ? this.user
        : (await api("/user/" + query.get("id"))).data;
      if (!this.profileUser) {
        throw new Error("这位用户暂时没有公开资料");
      }
      await Promise.all([this.isOwnProfile ? this.loadSignCount() : Promise.resolve(), this.loadBlogs(true)]);
    },
    async loadSignCount() {
      const sequence = ++this.signCountSequence;
      this.signCountLoading = true;
      this.signCountError = "";
      try {
        const result = await api("/user/sign/count");
        if (sequence !== this.signCountSequence) return false;
        this.signCount = Number(result.data || 0);
        return true;
      } catch {
        if (sequence === this.signCountSequence) this.signCountError = "签到天数暂未查到";
        return false;
      } finally {
        if (sequence === this.signCountSequence) this.signCountLoading = false;
      }
    },
    async sign() {
      if (!this.requireUser() || this.signBusy || this.signed) {
        return;
      }
      this.signBusy = true;
      try {
        await api("/user/sign", { method: "POST" });
        this.signed = true;
        const updated = await this.loadSignCount();
        this.notify(updated ? "签到成功，今天也要好好生活" : "签到成功，连续天数暂未更新，可稍后刷新查看");
      } catch (error) {
        this.showError(error);
      } finally {
        this.signBusy = false;
      }
    },
    async logout() {
      try {
        await api("/user/logout", { method: "POST", quiet: true });
      } catch (error) {
        if (error.status !== 401) {
          this.showError(error);
          return;
        }
      }
      sessionStorage.removeItem("token");
      location.assign("/");
    },
    async loadOrders(reset = false) {
      if (this.orderLoading && reset !== true) {
        return;
      }
      const sequence = ++this.orderSequence;
      if (reset === true) { this.orderPage = 1; this.orders = []; this.orderTotal = 0; }
      this.orderLoading = true;
      this.orderError = "";
      try {
        const result = await api(this.orderScope === "held" ? "/voucher-order/mine/vouchers" : "/voucher-order/mine", {
          params: { page: this.orderPage },
        });
        if (sequence !== this.orderSequence) return;
        this.orders = result.data || [];
        this.orderTotal = Number(result.total || 0);
      } catch (error) {
        if (sequence === this.orderSequence) this.orderError = error.message;
      } finally {
        if (sequence === this.orderSequence) this.orderLoading = false;
      }
    },
    async orderPageTo(pageNumber) {
      if (this.orderLoading || !Number.isSafeInteger(pageNumber) || pageNumber < 1) return;
      const previous = this.orderPage;
      this.orderPage = pageNumber;
      const pending = this.loadOrders();
      const sequence = this.orderSequence;
      await pending;
      if (sequence === this.orderSequence && this.orderError) this.orderPage = previous;
    },
    changeOrderScope(scope) {
      if (!["all","held"].includes(scope) || scope === this.orderScope) return;
      this.orderScope = scope;
      if (typeof history !== "undefined") {
        const url = new URL(location.href);
        if (scope === "held") url.searchParams.set("tab","held"); else url.searchParams.delete("tab");
        history.replaceState(null,"",url.pathname+url.search);
      }
      return this.loadOrders(true);
    },
    async watchRequest(attempt = 0) {
      const id = query.get("request");
      if (!id || !/^\d+$/.test(id)) {
        return;
      }
      try {
        this.pendingRequest = (await api("/voucher-order/requests/" + id)).data;
        const state = this.pendingRequest && this.pendingRequest.state;
        if (state === "SUCCESS") {
          this.requestMessage = "订单已创建，请在全部订单查看付款与领取状态";
          await this.loadOrders();
          return;
        }
        if (state === "FAILED") {
          this.requestMessage = "本次领取未成功，请返回店铺查看";
          return;
        }
        if (state === "REVIEW") {
          this.requestMessage = "领取结果仍在核对，请稍后重新查看";
          return;
        }
        this.requestMessage = "领取请求已受理，正在确认结果…";
        if (attempt < 10) {
          this.requestTimer = setTimeout(
            () => this.watchRequest(attempt + 1),
            2000,
          );
        } else {
          this.requestMessage = "订单仍在处理中，可稍后刷新查看，请勿重复提交";
        }
      } catch (error) {
        this.requestMessage = error.message;
      }
    },
    cancelOrder(order) {
      if (!this.requireUser() || this.cancelBusy || order.status !== 1 || order.voucher_type !== 0) return;
      this.dialog = { kind: "cancel", order };
    },
    async confirmCancel() {
      if (this.cancelBusy || !this.dialog || this.dialog.kind !== "cancel") return;
      const order = this.dialog.order;
      this.dialog = null;
      this.cancelBusy = true;
      try {
        await api("/voucher-order/" + order.order_id + "/cancel", {
          method: "POST",
        });
        await this.loadOrders(true);
        this.notify("订单已取消");
      } catch (error) {
        if (!error.status || error.status >= 500) this.notify("取消结果未确认，请先刷新订单核对状态，不要连续提交", "error");
        else this.showError(error);
      } finally {
        this.cancelBusy = false;
      }
    },
    async sendCode() {
      this.loginError = "";
      if (!/^1[3-9]\d{9}$/.test(this.phone.trim())) {
        this.loginError = "请输入正确的 11 位手机号码";
        return;
      }
      if (this.codeBusy || this.codeCountdown) {
        return;
      }
      this.codeBusy = true;
      try {
        await api("/user/code", {
          method: "POST",
          params: { phone: this.phone.trim() },
        });
        this.codeCountdown = 60;
        this.codeTimer = setInterval(() => {
          this.codeCountdown -= 1;
          if (this.codeCountdown <= 0) {
            clearInterval(this.codeTimer);
          }
        }, 1000);
        this.notify("验证码已生成，请使用当前演示环境提供的验证码");
      } catch (error) {
        this.loginError = error.message;
      } finally {
        this.codeBusy = false;
      }
    },
    async login() {
      if (this.loginBusy) return;
      if (
        !/^1[3-9]\d{9}$/.test(this.phone.trim()) ||
        !/^\d{6}$/.test(this.code.trim())
      ) {
        this.loginError = "请填写正确的手机号和 6 位验证码";
        return;
      }
      this.loginBusy = true;
      this.loginError = "";
      try {
        const result = await api("/user/login", {
          method: "POST",
          body: { phone: this.phone.trim(), code: this.code.trim() },
        });
        sessionStorage.setItem("token", String(result.data));
        location.assign(safeReturn(query.get("return"), location.origin));
      } catch (error) {
        this.loginError = error.message;
      } finally {
        this.loginBusy = false;
      }
    },
    loadLocalDraft() {
      if (!this.user) return;
      try {
        const saved = readNoteDraft(localStorage.getItem(noteDraftKey(this.user.id)));
        if (saved && hasNoteContent(saved.draft)) {
          this.draftRecovery = saved;
          this.draftStorageReady = false;
          return;
        }
      } catch { this.draftSaveState = "unavailable"; }
      this.draftStorageReady = true;
    },
    async restoreLocalDraft() {
      if (!this.draftRecovery) return;
      this.draft = this.draftRecovery.draft;
      this.publishUncertain = this.draftRecovery.uncertain;
      this.draftRecovery = null;
      this.draftStorageReady = true;
      this.draftSaveState = "saved";
      await this.loadPublishShops();
    },
    startFreshDraft() {
      if (!this.user) return;
      try {
        localStorage.removeItem(noteDraftKey(this.user.id));
        this.draftSaveState = "idle";
      } catch { this.draftSaveState = "unavailable"; }
      this.draftRecovery = null;
      this.draftStorageReady = true;
      this.publishUncertain = false;
    },
    persistDraft(manual = false) {
      if (!this.user || !this.draftStorageReady || this.draftSaved) return false;
      try {
        const key = noteDraftKey(this.user.id);
        if (hasNoteContent(this.draft)) {
          localStorage.setItem(key, encodeNoteDraft(this.draft, this.publishUncertain));
          this.draftSaveState = "saved";
        } else {
          localStorage.removeItem(key);
          this.draftSaveState = "idle";
        }
        if (manual === true) this.notify("草稿已保存在此浏览器");
        return true;
      } catch {
        this.draftSaveState = "unavailable";
        if (manual === true) this.notify("暂时无法保存草稿，请保留当前页面或复制文字", "error");
        return false;
      }
    },
    clearLocalDraft() {
      if (!this.user || this.publishBusy || this.uploadBusy) return;
      try { localStorage.removeItem(noteDraftKey(this.user.id)); }
      catch { this.notify("本地草稿无法清除，请稍后再试", "error"); return; }
      this.draft = { title: "", content: "", shopId: this.draft.shopId, pictures: [] };
      this.publishUncertain = false;
      this.draftDiscardConfirm = false;
      this.draftSaveState = "idle";
    },
    acknowledgePublishCheck() {
      this.publishUncertain = false;
      this.persistDraft();
    },
    setNoteCover(picture) {
      if (this.uploadBusy || this.publishBusy || !this.draft.pictures.includes(picture)) return;
      this.draft.pictures = [picture, ...this.draft.pictures.filter(path => path !== picture)];
    },
    async loadPublishShops() {
      const sequence = ++this.publishShopSequence;
      const selected = this.draft.shopId;
      this.publishShopLoading = true;
      this.publishShopError = "";
      try {
        const rows =
          (
            await api("/shop/catalog", {
              params: {
                keyword: this.shopQuery.trim(),
                size: 20,
                sort: "score",
              },
            })
          ).data || [];
        if (selected && !rows.some(row => String(row.id) === selected)) {
          const shop = (await api("/shop/" + encodeURIComponent(selected))).data;
          if (shop && String(shop.id) === selected) rows.unshift(shop);
          else if (sequence === this.publishShopSequence) {
            this.publishShopError = "原关联店铺暂不可用，请重新选择";
            this.draft.shopId = "";
          }
        }
        if (sequence === this.publishShopSequence) this.publishShops = rows;
      } catch (error) {
        if (sequence === this.publishShopSequence) this.publishShopError = error.message;
      } finally {
        if (sequence === this.publishShopSequence) this.publishShopLoading = false;
      }
    },
    async upload(event) {
      if (this.uploadBusy || this.publishBusy) return;
      const files = Array.from(event.target.files || []);
      if (files.length + this.draft.pictures.length > 9) {
        this.notify("最多上传 9 张图片", "error");
        event.target.value = "";
        return;
      }
      if (
        files.some(
          (file) =>
            file.size > 5000000 || !/\.(jpg|jpeg|png|webp)$/i.test(file.name),
        )
      ) {
        this.notify("请选择 5 MB 以内的 JPG、PNG 或 WebP 图片", "error");
        event.target.value = "";
        return;
      }
      this.uploadBusy = true;
      try {
        for (const file of files) {
          const body = new FormData();
          body.append("file", file);
          const result = await api("/upload/blog", { method: "POST", body });
          this.draft.pictures.push(String(result.data));
        }
      } catch (error) {
        this.showError(error);
      } finally {
        this.uploadBusy = false;
        event.target.value = "";
      }
    },
    async removePicture(path) {
      if (this.uploadBusy || this.publishBusy) return;
      try {
        await api("/upload/blog", { method: "DELETE", params: { name: path } });
        this.draft.pictures = this.draft.pictures.filter(
          (item) => item !== path,
        );
      } catch (error) {
        this.showError(error);
      }
    },
    async saveBlog() {
      if (this.publishBusy || this.uploadBusy || this.publishUncertain || !this.draftStorageReady) return;
      if (!this.requireUser()) return;
      const publishingUser = this.user.id;
      if (
        !this.draft.title.trim() ||
        !this.draft.content.trim() ||
        !this.draft.shopId || !this.publishShops.some(row => String(row.id) === this.draft.shopId)
      ) {
        this.notify("请填写标题、探店内容并选择关联店铺", "error");
        return;
      }
      this.persistDraft();
      this.publishBusy = true;
      try {
        const result = await api("/blog", {
          method: "POST",
          body: {
            title: this.draft.title.trim(),
            content: this.draft.content.trim(),
            shopId: this.draft.shopId,
            images: this.draft.pictures.join(","),
          },
        });
        if (!/^[1-9]\d*$/.test(String(result.data || ""))) {
          this.publishUncertain = true;
          this.persistDraft();
          this.notify("发布结果还未确认，请先到个人主页核对", "error");
          return;
        }
        this.draftSaved = true;
        clearTimeout(this.draftTimer);
        try { localStorage.removeItem(noteDraftKey(publishingUser)); } catch { /* Keep publish success. */ }
        location.assign("/blog-detail.html?id=" + result.data);
      } catch (error) {
        if (!error.status || error.status >= 500) {
          this.publishUncertain = true;
          this.persistDraft();
        }
        this.showError(error);
      } finally {
        this.publishBusy = false;
      }
    },
  },
});

window.addEventListener("beforeunload", (event) => {
  const locallySaved = page === "publish" && app.persistDraft();
  if (
    page === "publish" &&
    !app.draftSaved &&
    (app.publishBusy || app.uploadBusy || (!locallySaved && hasNoteContent(app.draft)))
  ) {
    event.preventDefault();
    event.returnValue = "";
  }
});

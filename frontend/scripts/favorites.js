import {contextualHref, imagePath} from './api.js';

export const favoriteButton = {
  props: {kind: String, id: [String, Number], user: Object, userReady: Boolean, api: Function},
  data() { return {saved: false, known: false, loading: false, busy: false, error: '', sequence: 0}; },
  computed: {
    targetName() { return this.kind === 'shop' ? '店铺' : '笔记'; },
    label() {
      if(this.loading)return '查询收藏…';
      if(this.busy)return '正在保存…';
      if(this.user && !this.known)return '收藏状态待确认';
      return (this.saved?'已收藏':'收藏')+this.targetName;
    },
  },
  mounted() { this.load(); },
  beforeDestroy() { this.sequence++; },
  watch: {user() { this.load(); }, userReady() { this.load(); }, id() { this.load(); }, kind() { this.load(); }},
  methods: {
    async load() {
      const seq=++this.sequence;this.saved=false;this.known=false;this.error='';this.busy=false;this.loading=false;
      if (!this.user || !this.userReady) return;
      const owner=String(this.user.id);this.loading=true;
      try {
        const response=await this.api('/favorites/'+this.kind+'/'+this.id,{quiet:true});
        if (seq!==this.sequence || String(this.user?.id)!==owner) return;
        if (typeof response.data!=='boolean') throw new Error('收藏状态暂不可用');
        this.saved=response.data;this.known=true;
      } catch { if(seq===this.sequence) this.error='收藏状态暂不可用，请重新查询。'; }
      finally { if(seq===this.sequence) this.loading=false; }
    },
    async toggle() {
      if (!this.userReady || this.loading || this.busy) return;
      if (!this.user) {
        location.assign('/login.html?return='+encodeURIComponent(location.pathname+location.search+(location.hash||'')));return;
      }
      if (!this.known) return;
      const owner=String(this.user.id),seq=++this.sequence,wanted=!this.saved;this.busy=true;this.error='';
      try {
        const response=await this.api('/favorites/'+this.kind+'/'+this.id,{method:'PUT',body:{saved:wanted},quiet:true});
        if(seq!==this.sequence || String(this.user?.id)!==owner) return;
        if(response.data!==wanted) throw new Error('收藏状态需核对');
        this.saved=wanted;
      } catch {
        if(seq===this.sequence) {this.known=false;this.error='状态可能已变化，请重新查询后操作。';}
      } finally {if(seq===this.sequence)this.busy=false;}
    },
  },
  template: `<div class="favorite-control"><button type="button" class="button outline favorite-button" :class="{'is-saved':saved && known}" :disabled="!userReady || loading || busy || (!!user && !known)" :aria-pressed="!known && user ? 'mixed' : saved ? 'true' : 'false'" @click="toggle"><i :class="saved && known ? 'el-icon-star-on' : 'el-icon-star-off'" aria-hidden="true"></i>{{ label }}</button><p v-if="error" role="status">{{ error }} <button type="button" class="text-button" :disabled="loading || busy" @click="load">重新查询收藏</button></p></div>`,
};

export const favoriteList = {
  props: {user: Object, api: Function},
  data() {return {kind:'shop',items:[],page:1,total:0,loading:false,error:'',sequence:0,removing:'',uncertain:false};},
  mounted() {if(new URLSearchParams(location.search).get('collection')==='note')this.kind='note';this.load(true);},
  beforeDestroy() {this.sequence++;},
  watch: {user() {this.sequence++;this.removing='';this.loading=false;this.uncertain=false;this.load(true);}},
  methods: {
    imagePath,
    href(item) {return contextualHref((item.kind==='shop'?'/shop-detail.html?id=':'/blog-detail.html?id=')+item.id,
      '/info.html?collection='+this.kind+'#my-favorites',location.origin);},
    async choose(kind) {
      if(this.loading||this.removing||!['shop','note'].includes(kind)||kind===this.kind)return;
      this.kind=kind;
      if(typeof history!=='undefined'){
        const url=new URL(location.href);url.searchParams.set('collection',kind);url.hash='my-favorites';
        history.replaceState(null,'',url.pathname+url.search+url.hash);
      }
      await this.load(true);
    },
    async load(reset=false) {
      if(this.removing || (this.loading&&!reset))return;
      const seq=++this.sequence,owner=String(this.user?.id||'');
      if(reset){this.page=1;this.items=[];this.total=0;}
      if(!this.user){this.loading=false;return;}
      this.loading=true;this.error='';const requested=this.page;
      try{
        const response=await this.api('/favorites',{params:{kind:this.kind,page:requested,size:12},quiet:true});
        if(seq!==this.sequence||owner!==String(this.user?.id||''))return;
        if(!Array.isArray(response.data))throw new Error('invalid list');
        const seen=new Set(this.items.map(i=>String(i.id)));
        response.data.forEach(item=>{if(!seen.has(String(item.id))){this.items.push(item);seen.add(String(item.id));}});
        this.total=Number(response.total||0);this.page=requested+1;this.uncertain=false;
      }catch{if(seq===this.sequence)this.error='收藏清单加载失败，请重试。';}
      finally{if(seq===this.sequence)this.loading=false;}
    },
    async remove(item){
      if(this.loading||this.removing||!this.user||this.uncertain)return;
      const seq=++this.sequence,owner=String(this.user.id);this.removing=String(item.id);this.error='';
      try{
        const response=await this.api('/favorites/'+this.kind+'/'+item.id,{method:'PUT',body:{saved:false},quiet:true});
        if(seq!==this.sequence||owner!==String(this.user?.id||''))return;
        if(response.data!==false)throw new Error('unknown removal');
        // Re-read from page one: deleting a row shifts every later offset.
        this.removing='';await this.load(true);
      }catch{if(seq===this.sequence){this.uncertain=true;this.error='取消结果待确认，请刷新收藏清单。';}}
      finally{if(seq===this.sequence)this.removing='';}
    },
  },
  template:`<section id="my-favorites" class="favorites-panel" aria-labelledby="favorites-title"><div class="section-heading"><h2 id="favorites-title">我的收藏</h2><span class="section-caption">留住想去的店和喜欢的笔记</span></div><div class="favorites-tabs" role="group" aria-label="收藏类型"><button type="button" :aria-pressed="kind==='shop'" :class="{active:kind==='shop'}" :disabled="loading || !!removing" @click="choose('shop')">想去的店</button><button type="button" :aria-pressed="kind==='note'" :class="{active:kind==='note'}" :disabled="loading || !!removing" @click="choose('note')">收藏的笔记</button></div><p v-if="error" role="status" class="favorite-error">{{ error }} <button class="text-button" type="button" :disabled="loading || !!removing" @click="load(uncertain)">重新加载收藏</button></p><p v-if="loading" role="status">正在查询收藏…</p><div class="favorites-grid"><article v-for="item in items" :key="item.kind+':'+item.id" class="favorite-item"><a v-if="item.available" :href="href(item)"><app-image :src="imagePath((item.images||'').split(',')[0])" :alt="item.title"></app-image><div><h3>{{ item.title }}</h3><p>{{ item.area || (kind==='note'?'探店笔记':'店铺详情') }}</p><strong v-if="kind==='shop' && item.avgPrice!=null">人均 ¥{{ item.avgPrice }}</strong></div></a><div v-else class="favorite-unavailable"><i class="el-icon-document-delete" aria-hidden="true"></i><h3>内容已下架或不可用</h3><p>可以从收藏中移除</p></div><button type="button" class="text-button favorite-remove" :aria-label="'取消收藏：'+(item.title||'已下架内容')" :disabled="loading || !!removing || uncertain" @click="remove(item)">{{ removing===String(item.id)?'正在取消…':'取消收藏' }}</button></article></div><div v-if="!loading && !error && !items.length" class="favorites-empty"><i class="el-icon-star-off" aria-hidden="true"></i><h3>{{ kind==='shop'?'还没有想去的店':'还没有收藏笔记' }}</h3><a class="button outline" :href="kind==='shop'?'/shop-list.html':'/'">{{ kind==='shop'?'发现好店':'看看探店笔记' }}</a></div><button v-if="items.length<total" type="button" class="button soft" :disabled="loading || !!removing || uncertain" @click="load()">加载更多收藏</button></section>`,
};

if(typeof Vue!=='undefined'){Vue.component('favorite-button',favoriteButton);Vue.component('favorite-list',favoriteList);}

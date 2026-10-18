const { contextBridge, ipcRenderer } = require('electron');

const call = (ch) => (...a) => ipcRenderer.invoke(ch, ...a);

contextBridge.exposeInMainWorld('api', {
  search: call('search'),
  play: call('play'),
  toggle: call('toggle'),
  next: call('next'),
  prev: call('prev'),
  seek: call('seek'),
  shuffle: call('shuffle'),
  repeat: call('repeat'),
  fav: call('fav'),
  favorites: call('favorites'),
  brave: call('brave'),
  pin: call('pin'),
  getPin: call('getPin'),
  minimize: call('minimize'),
  close: call('close'),
  retry: call('retry'),
  pickBrave: call('pickBrave'),
  onState: (cb) => ipcRenderer.on('state', (_e, s) => cb(s)),
  onStatus: (cb) => ipcRenderer.on('status', (_e, s) => cb(s)),
});

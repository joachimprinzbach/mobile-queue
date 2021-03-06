import { Component } from '@angular/core';
import { NavController } from 'ionic-angular';
import { TicketsService } from '../../app/tickets.service';
import { Observable } from 'rxjs/Observable';
import { TranslateService } from '@ngx-translate/core';

@Component({
  selector: 'page-settings',
  templateUrl: 'settings.html',
})
export class SettingsPage {
  subscr: any;
  loginFailed: string;

  get lastMessages():string[] {
    return this.ws.lastMessages;
  };

  constructor(public navCtrl: NavController, public ws: TicketsService,
    private translate: TranslateService) {
    ws.loginFailed.subscribe(lf => {
      this.loginFailed = lf.passwordRequired ? 'Password required' : 'User exists already';
    });
  }

  get username() {
    return this.ws.getUsername();
  }
  set username(name: string) {
    this.ws.setUsername(name);
  }

  password = '';
  
  connectedState(): Observable<boolean> {
    return this.ws.connected;
  }

  loggedIn(): Observable<boolean> {
    return this.ws.identified;
  }

  stopped() {
    this.loginFailed = '';
    return this.ws.stopped;
  }

  loggedInText(): Observable<any> {
    if (this.loginFailed) {
      return Observable.of(this.loginFailed);
    }
    return this.ws.identified.map(c => c ? this.translate.instant("messages.UserConnected", {"username": this.ws.getUsername()}) : this.translate.instant("messages.UserDisconnected", {"username": this.ws.getUsername()}));
  }

  logIn(name, password) {
    this.loginFailed = '';
    this.subscr = this.ws.identified.subscribe(e => {
      if (e) {
        this.navCtrl.pop();

        this.subscr.unsubscribe();
      }
    });
    this.ws.login(name, password);
  }

  logOut() {
    this.loginFailed = '';
    this.ws.disconnectWS();
  }
  
}

import { Component, Input, Output, EventEmitter, OnDestroy, OnInit } from '@angular/core';
import { TicketsService, EventSubscription, UserTicketSummary, TicketMessage, Ticket } from '../../app/tickets.service';
import { formatCurrentMoment } from '../../app/utils';
import { Vibration } from '@ionic-native/vibration';
import { Subscription } from 'rxjs';
import { Observable } from 'rxjs/Observable';
import { BackgroundMode } from '@ionic-native/background-mode';
import { LocalNotifications } from '@ionic-native/local-notifications';
import { Toast } from '@ionic-native/toast';
import { SubscribePage } from '../../pages/subscribe/subscribe';
import { Platform } from 'ionic-angular';

@Component({
  selector: 'ticket',
  templateUrl: 'ticket.html'
})
export class TicketComponent implements OnInit, OnDestroy {

  ticketSummary: UserTicketSummary;
  lastMessageTitle: string;
  lastMessage: string;
  cancelled: boolean;

  get called(): boolean {
    return this.subscribedEvent.ticket.state === 'Called';
  }

  get accepted(): boolean {
    return this.subscribedEvent.ticket.state === 'Confirmed';
  }

  get eventTitle() {
    return this.subscribedEvent ? this.subscribedEvent.description : '';
  }

  get subscribedEventParticipants() {
    return this.subscribedEvent.ticket.participants;
  }
  get connectedState(): Observable<boolean> {
    return this.ws.connected;
  }

  get loggedIn(): Observable<boolean> {
    return this.ws.identified;
  }

  private summariesSubsription: Subscription;
  private acceptedSubsription: Subscription;
  private calledSubsription: Subscription;
  private activatedSubsription: Subscription;
  private closedSubscription: Subscription;
  private expiredSubscription: Subscription;
  private skippedSubscription: Subscription;
  
  constructor(private ws: TicketsService, private vibration :Vibration, public platform: Platform,
    private backgroundMode: BackgroundMode, private localNotifications: LocalNotifications, private toast: Toast) {
      
  }

  private showTostMessage() {
    if (this.platform.is('cordova')) {
      try {
        this.toast.showLongBottom(this.lastMessage).subscribe();
      } catch (e) {
  
      }
    }
  }

  ngOnInit(): void {
    console.log("create ticket " + JSON.stringify(this.subscribedEvent))
    this.lastMessageTitle = `${formatCurrentMoment()} - Ticket registered`;
    this.lastMessage = `You'll be called 10 minutes before Your Event "${this.eventTitle}" starts!`;
    this.cancelled = false;
    const filterMyTicketChannel = (msg: TicketMessage) => msg && msg.ticket.id === this.subscribedEvent.ticket.id && msg.ticket.eventid == this.subscribedEvent.ticket.eventid;
    const filterMyTicketSummaryChannel = (summary: UserTicketSummary) => summary && summary.eventid === this.subscribedEvent.ticket.eventid;

    this.activatedSubsription = this.ws.ticketActivated.filter(filterMyTicketChannel).subscribe(msg => {
      this.subscribedEvent.ticket = msg.ticket;
      this.lastMessageTitle = `${formatCurrentMoment()} - Ticket (re-)activated`;
      this.lastMessage = `You will be called 10 minutes before Your Event "${this.eventTitle}" starts!`;
      this.showTostMessage();
    });
    this.calledSubsription = this.ws.ticketCalled.filter(filterMyTicketChannel).subscribe(msg =>{
      this.subscribedEvent.ticket = msg.ticket;
      this.vibration.vibrate([1000 , 500 , 2000]);
      this.lastMessageTitle = `${formatCurrentMoment()} - Let's go`;
      this.lastMessage = "Please confirm. Will you be ready in 10 minutes?";
      if (this.platform.is('cordova')) {
        try {
          this.localNotifications.schedule({
            id: msg.ticket.eventid,
            text: this.lastMessage
          });
          this.backgroundMode.isScreenOff().then((off: boolean) => {
            try {
              if (off) {
                this.backgroundMode.unlock();
              } else {
                this.backgroundMode.moveToForeground();
              }
            } catch (e) {}
          });
        } catch (e) {}
      }
    });
    this.acceptedSubsription = this.ws.ticketAccepted.filter(filterMyTicketChannel).subscribe(msg => {
      this.subscribedEvent.ticket = msg.ticket;
      this.lastMessageTitle = `${formatCurrentMoment()} - Ticket accepted`;
      this.lastMessage = `We expect you in 10 minutes at ${this.eventTitle}!`;
      this.showTostMessage();
    });
    this.skippedSubscription = this.ws.ticketSkipped.filter(filterMyTicketChannel).subscribe(msg => {
      this.subscribedEvent.ticket = msg.ticket;
      this.lastMessageTitle = `${formatCurrentMoment()} - Ticket skipped`;
      this.lastMessage = `You will be called 10 minutes before the next iteration of Your Event "${this.eventTitle}" starts!`;
      this.showTostMessage();
    });
    this.expiredSubscription = this.ws.ticketExpired.filter(filterMyTicketChannel).subscribe(msg => {
      this.subscribedEvent.ticket = msg.ticket;
      this.lastMessageTitle = `${formatCurrentMoment()} - Ticket has expired`;
      this.lastMessage = `You will be called 10 minutes before the next iteration of Your Event "${this.eventTitle}" starts!`;
      this.showTostMessage();
    });
    this.closedSubscription = this.ws.ticketDeleted.filter(filterMyTicketChannel).subscribe(msg => {
      this.subscribedEvent.ticket = msg.ticket;
      this.cancelled = true;
      this.lastMessageTitle = `${formatCurrentMoment()} - Ticket returned`;
      this.lastMessage = `You're no longer waiting for ${this.eventTitle}!`; 
      this.showTostMessage();
    });
    this.summariesSubsription = this.ws.ticketSummaries.filter(filterMyTicketSummaryChannel).subscribe((summary: UserTicketSummary) => {
      this.ticketSummary = summary;
    });    
  }

  @Input()
  subscribedEvent: EventSubscription;

  @Output()
  onClose = new EventEmitter<Ticket>();

  skip(slidingItem) {
    this.lastMessageTitle = `${formatCurrentMoment()} - I'm Not ready yet`;
    this.lastMessage = "I skipped my invitation to the next iteration";
    this.ws.skip(this.subscribedEvent.ticket);
    slidingItem.close();
  }

  accept(slidingItem) {
    this.lastMessageTitle = `${formatCurrentMoment()} - Confirmed`;
    this.lastMessage = "Yes, i'll be there";
    this.ws.confirm(this.subscribedEvent.ticket);
    slidingItem.close();
  }

  cancel(slidingItem) {
    this.ws.unsubscribeEvent(this.subscribedEvent.ticket.eventid);
    slidingItem.close();
  }

  close(slidingItem) {
    this.onClose.next(this.subscribedEvent.ticket);   
    slidingItem.close(); 
  }

  ngOnDestroy(): void {
    console.log("destroy ticket " + this.subscribedEvent)
    this.clearSubsriptions();
  }

  clearSubsriptions() {
    if (this.summariesSubsription) {
      this.summariesSubsription.unsubscribe();
      this.acceptedSubsription.unsubscribe();
      this.calledSubsription.unsubscribe();
      this.activatedSubsription.unsubscribe();
      this.closedSubscription.unsubscribe();
      this.expiredSubscription.unsubscribe();
      this.skippedSubscription.unsubscribe();
    }
  }
}
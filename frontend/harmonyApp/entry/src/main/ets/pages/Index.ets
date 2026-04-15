/**
 * 主页面 - 路由入口
 * 单入口 + 自定义路由状态切换
 */
import { router as appRouter, PageName } from '../common/Router';
import { LoginPage } from './LoginPage';
import { MainPage } from './MainPage';
import { ChatPage } from './ChatPage';
import { TodoPage } from './TodoPage';
import { SettingsPage } from './SettingsPage';

@Entry
@Component
struct Index {
  @State currentPage: PageName = PageName.Login;

  aboutToAppear(): void {
    this.currentPage = appRouter.getCurrentPage();
    appRouter.onPageChange((page: PageName): void => {
      this.currentPage = page;
    });
  }

  build() {
    Column() {
      if (this.currentPage === PageName.Login) {
        LoginPage();
      } else if (this.currentPage === PageName.Main || this.currentPage === PageName.GroupList) {
        MainPage();
      } else if (this.currentPage === PageName.Chat) {
        ChatPage();
      } else if (this.currentPage === PageName.Todo) {
        TodoPage();
      } else if (this.currentPage === PageName.Settings) {
        SettingsPage();
      } else {
        MainPage();
      }
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Start)
    .alignItems(HorizontalAlign.Start);
  }
}

import { SilkColors } from '../common/Theme';
import { router as appRouter } from '../common/Router';
import { GroupListPage } from './GroupListPage';
import { WorkflowPage } from './WorkflowPage';
import { KnowledgeBasePage } from './KnowledgeBasePage';

@Component
export struct MainPage {
  @State currentTab: number = 0;

  build() {
    Tabs({ barPosition: BarPosition.End, index: this.currentTab }) {
      TabContent() {
        GroupListPage()
      }
      .tabBar(this.buildTabItem(0, '💬', 'Silk'))

      TabContent() {
        WorkflowPage()
      }
      .tabBar(this.buildTabItem(1, '🔗', '工作流'))

      TabContent() {
        KnowledgeBasePage()
      }
      .tabBar(this.buildTabItem(2, '📚', '知识库'))
    }
    .width('100%')
    .height('100%')
    .barMode(BarMode.Fixed)
    .onChange((index: number) => {
      this.currentTab = index;
    })
  }

  @Builder
  buildTabItem(index: number, icon: string, label: string) {
    Column() {
      Text(icon)
        .fontSize(22)
      Text(label)
        .fontSize(11)
        .fontColor(this.currentTab === index ? SilkColors.primary : SilkColors.textLight)
        .margin({ top: 2 })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }
}
